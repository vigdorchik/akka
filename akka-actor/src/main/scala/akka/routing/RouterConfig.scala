/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import scala.collection.immutable
import akka.ConfigurationException
import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorPath
import akka.actor.AutoReceivedMessage
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.actor.Terminated
import akka.dispatch.Dispatchers
import akka.actor.ActorSystem

/**
 * This trait represents a router factory: it produces the actual router actor
 * and creates the routing table (a function which determines the recipients
 * for each message which is to be dispatched). The resulting RoutedActorRef
 * optimizes the sending of the message so that it does NOT go through the
 * router’s mailbox unless the route returns an empty recipient set.
 *
 * '''Caution:''' This means
 * that the route function is evaluated concurrently without protection by
 * the RoutedActorRef: either provide a reentrant (i.e. pure) implementation or
 * do the locking yourself!
 *
 * '''Caution:''' Please note that the [[akka.routing.Router]] which needs to
 * be returned by `createActor()` should not send a message to itself in its
 * constructor or `preStart()` or publish its self reference from there: if
 * someone tries sending a message to that reference before the constructor of
 * RoutedActorRef has returned, there will be a `NullPointerException`!
 */
@SerialVersionUID(1L)
trait RouterConfig extends Serializable {

  /**
   * Create the actual router, responsible for routing messages to routees.
   * @param system the ActorSystem this router belongs to
   */
  def createRouter(system: ActorSystem): Router

  /**
   * INTERNAL API
   * The router "head" actor.
   */
  private[akka] def createRouterActor(): RouterActor

  /**
   * Dispatcher ID to use for running the “head” actor, which handles
   * supervision, death watch and router management messages
   */
  def routerDispatcher: String

  // FIXME #3549 try to make more things INTERNAL API

  /**
   * Possibility to define an actor for controlling the routing
   * logic from external stimuli (e.g. monitoring metrics).
   * This actor will be a child of the router "head" actor.
   * Managment messages not handled by the "head" actor are
   * delegated to this controller actor.
   */
  def routingLogicController(routingLogic: RoutingLogic): Option[Props] = None

  /**
   * Is the message handled by the router head actor
   */
  def isManagementMessage(msg: Any): Boolean = msg match {
    case _: AutoReceivedMessage | _: Terminated | _: RouterManagementMesssage ⇒ true
    case _ ⇒ false
  }

  /*
   * Specify that this router should stop itself when all routees have terminated (been removed).
   * By Default it is `true`, unless a `resizer` is used.
   */
  def stopRouterWhenAllRouteesRemoved: Boolean = true

  /**
   * Overridable merge strategy, by default completely prefers “this” (i.e. no merge).
   */
  def withFallback(other: RouterConfig): RouterConfig = this

  /**
   * Check that everything is there which is needed. Called in constructor of RoutedActorRef to fail early.
   */
  def verifyConfig(path: ActorPath): Unit = ()

}

/**
 * INTERNAL API
 *
 * Used to override unset configuration in a router.
 */
private[akka] trait PoolOverrideUnsetConfig[T <: Pool] extends Pool {

  final def overrideUnsetConfig(other: RouterConfig): RouterConfig =
    if (other == NoRouter) this // NoRouter is the default, hence “neutral”
    else {

      other match {
        case p: Pool ⇒
          val wssConf: PoolOverrideUnsetConfig[T] =
            if ((this.supervisorStrategy eq Pool.defaultSupervisorStrategy)
              && (p.supervisorStrategy ne Pool.defaultSupervisorStrategy))
              this.withSupervisorStrategy(p.supervisorStrategy).asInstanceOf[PoolOverrideUnsetConfig[T]]
            else this

          if (wssConf.resizer.isEmpty && p.resizer.isDefined)
            wssConf.withResizer(p.resizer.get)
          else
            wssConf
        case _ ⇒ this
      }
    }

  def withSupervisorStrategy(strategy: SupervisorStrategy): T

  def withResizer(resizer: Resizer): T
}

trait Nozzle extends RouterConfig {

  def paths: immutable.Iterable[String]

  /**
   * INTERNAL API
   */
  private[akka] def routeeFor(path: String, context: ActorContext): Routee =
    ActorSelectionRoutee(context.actorSelection(path))

  /**
   * INTERNAL API
   */
  private[akka] override def createRouterActor(): RouterActor = new RouterActor
}

object Pool {
  val defaultSupervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }
}

trait Pool extends RouterConfig {
  /**
   * Initial number of routee instances
   */
  def nrOfInstances: Int

  /**
   * INTERNAL API
   */
  private[akka] def newRoutee(routeeProps: Props, context: ActorContext): Routee =
    ActorRefRoutee(context.actorOf(routeeProps))

  /**
   * Pool with dynamically resizable number of routees return the [[akka.routing.Resizer]]
   * to use. The resizer is invoked once when the router is created, before any messages can
   * be sent to it. Resize is also triggered when messages are sent to the routees, and the
   * resizer is invoked asynchronously, i.e. not necessarily before the message has been sent.
   */
  def resizer: Option[Resizer]

  /**
   * SupervisorStrategy for the head actor, i.e. for supervising the routees of the pool.
   */
  def supervisorStrategy: SupervisorStrategy

  /*
   * Specify that this router should stop itself when all routees have terminated (been removed).
   * By Default it is `true`, unless a `resizer` is used.
   */
  override def stopRouterWhenAllRouteesRemoved: Boolean = resizer.isEmpty

  /**
   * INTERNAL API
   */
  private[akka] override def createRouterActor(): RouterActor =
    resizer match {
      case None    ⇒ new RouterPoolActor(supervisorStrategy)
      case Some(r) ⇒ new ResizablePoolActor(supervisorStrategy)
    }

}

abstract class CustomRouterConfig extends RouterConfig {
  /**
   * INTERNAL API
   */
  private[akka] override def createRouterActor(): RouterActor = new RouterActor

  override def routerDispatcher: String = Dispatchers.DefaultDispatcherId
}

/**
 * Router configuration which has no default, i.e. external configuration is required.
 */
case object FromConfig extends FromConfig {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
  @inline final def apply(
    resizer: Option[Resizer] = None,
    supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy,
    routerDispatcher: String = Dispatchers.DefaultDispatcherId) =
    new FromConfig(resizer, supervisorStrategy, routerDispatcher)

  @inline final def unapply(fc: FromConfig): Option[String] = Some(fc.routerDispatcher)
}

/**
 * Java API: Router configuration which has no default, i.e. external configuration is required.
 *
 * This can be used when the dispatcher to be used for the head Router needs to be configured
 * (defaults to default-dispatcher).
 */
@SerialVersionUID(1L)
class FromConfig(override val resizer: Option[Resizer],
                 override val supervisorStrategy: SupervisorStrategy,
                 override val routerDispatcher: String) extends Pool {

  def this() = this(None, Pool.defaultSupervisorStrategy, Dispatchers.DefaultDispatcherId)

  override def createRouter(system: ActorSystem): Router =
    throw new UnsupportedOperationException("FromConfig must not create Router")

  /**
   * INTERNAL API
   */
  override private[akka] def createRouterActor(): RouterActor =
    throw new UnsupportedOperationException("FromConfig must not create RouterActor")

  override def verifyConfig(path: ActorPath): Unit =
    throw new ConfigurationException(s"Configuration missing for router [$path] in 'akka.actor.deployment' section.")

  /**
   * Setting the supervisor strategy to be used for the “head” Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): FromConfig =
    new FromConfig(resizer, strategy, routerDispatcher)

  /**
   * Setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): FromConfig =
    new FromConfig(Some(resizer), supervisorStrategy, routerDispatcher)

  /**
   * Setting the dispatcher to be used for the router head actor,  which handles
   * supervision, death watch and router management messages.
   */
  def withDispatcher(dispatcherId: String): FromConfig =
    new FromConfig(resizer, supervisorStrategy, dispatcherId)

  override val nrOfInstances: Int = 0

}

/**
 * Routing configuration that indicates no routing; this is also the default
 * value which hence overrides the merge strategy in order to accept values
 * from lower-precedence sources. The decision whether or not to create a
 * router is taken in the LocalActorRefProvider based on Props.
 */
@SerialVersionUID(1L)
abstract class NoRouter extends RouterConfig
case object NoRouter extends NoRouter {
  override def createRouter(system: ActorSystem): Router = throw new UnsupportedOperationException("NoRouter has no Router")
  /**
   * INTERNAL API
   */
  override private[akka] def createRouterActor(): RouterActor =
    throw new UnsupportedOperationException("NoRouter must not create RouterActor")
  override def routerDispatcher: String = throw new UnsupportedOperationException("NoRouter has no dispatcher")
  override def withFallback(other: akka.routing.RouterConfig): akka.routing.RouterConfig = other

  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * INTERNAL API
 */
@SerialVersionUID(1L) private[akka] trait RouterManagementMesssage

/**
 * Sending this message to a router will make it send back its currently used routees.
 * A [[Routees]] message is sent asynchronously to the "requester" containing information
 * about what routees the router is routing over.
 */
@SerialVersionUID(1L) abstract class GetRoutees extends RouterManagementMesssage

@SerialVersionUID(1L) case object GetRoutees extends GetRoutees {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * Message used to carry information about what routees the router is currently using.
 */
@SerialVersionUID(1L)
case class Routees(routees: immutable.IndexedSeq[Routee]) {
  /**
   * Java API
   */
  def getRoutees: java.util.List[Routee] = {
    import scala.collection.JavaConverters._
    routees.asJava
  }
}

@SerialVersionUID(1L)
case class AddRoutee(routee: Routee) extends RouterManagementMesssage

@SerialVersionUID(1L)
case class RemoveRoutee(routee: Routee) extends RouterManagementMesssage

@SerialVersionUID(1L)
case class AdjustPoolSize(change: Int) extends RouterManagementMesssage