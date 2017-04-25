package com.thoughtworks

import scala.language.higherKinds

object tryt {
  import scala.util.control.{NoStackTrace, NonFatal}
  import scala.util.{Failure, Success, Try}
  import scalaz.{\/, _}
  import scala.language.higherKinds
  import scalaz.Tags.Parallel

  private[tryt] trait TryTExtractor {
    type TryT[F[_], A]

    def apply[F[_], A](run: F[Try[A]]): TryT[F, A]
    def unwrap[F[_], A](tryT: TryT[F, A]): F[Try[A]]

  }
  //TODO : @delegate
  private[tryt] val extractor: TryTExtractor = new TryTExtractor {

    type TryT[F[_], A] = F[Try[A]]

    override def apply[F[_], A](tryT: F[Try[A]]): TryT[F, A] = tryT

    override def unwrap[F[_], A](tryT: TryT[F, A]): F[Try[A]] = tryT

  }

  object TryT extends TryTInstances0 {

    /** An exception that contains multiple Throwables. */
    private[tryt] final case class MultipleException(throwableSet: Set[Throwable])
        extends Exception("Multiple exceptions found")
        with NoStackTrace {
      override def toString: String = throwableSet.toString()
    }

    private[tryt] implicit def throwableSemigroup = new Semigroup[Throwable] {
      override def append(f1: Throwable, f2: => Throwable): Throwable =
        f1 match {
          case MultipleException(exceptionSet1) =>
            f2 match {
              case MultipleException(exceptionSet2) => MultipleException(exceptionSet1 ++ exceptionSet2)
              case _: Throwable => MultipleException(exceptionSet1 + f2)
            }
          case _: Throwable =>
            f2 match {
              case MultipleException(exceptionSet2) => MultipleException(exceptionSet2 + f1)
              case _: Throwable => MultipleException(Set(f1, f2))
            }
        }
    }
    private[thoughtworks] def unwrap[F[_], A](tryT: TryT[F, A]): F[Try[A]] = extractor.unwrap(tryT)
    def unapply[F[_], A](tryT: TryT[F, A]): Some[F[Try[A]]] = Some(unwrap(tryT))
    def apply[F[_], A](tryT: F[Try[A]]): TryT[F, A] = extractor.apply(tryT)
  }

  private[tryt] sealed abstract class TryTInstances3 { this: TryT.type =>
    implicit def tryTParallelApplicative[F[_]](
        implicit F0: Applicative[Lambda[x => F[x] @@ Parallel]]): Applicative[Lambda[x => TryT[F, x] @@ Parallel]] = {
      new TryTParallelApplicative[F] {
        override implicit def F: Applicative[Lambda[x => F[x] @@ Parallel]] = F0
      }
    }
  }

  private[tryt] sealed abstract class TryTInstances2 extends TryTInstances3 { this: TryT.type =>
    implicit def tryTBindRec[F[_]](implicit F0: Monad[F], B0: BindRec[F]): BindRec[TryT[F, ?]] = {
      new TryTBindRec[F] {
        override implicit def B: BindRec[F] = B0
        override implicit def F: Monad[F] = F0
      }
    }
  }

  private[tryt] sealed abstract class TryTInstances1 extends TryTInstances2 { this: TryT.type =>
    implicit def tryTMonadError[F[_]](implicit F0: Monad[F]): MonadError[TryT[F, ?], Throwable] = {
      new TryTMonadError[F] {
        implicit override def F: Monad[F] = F0
      }
    }
  }

  private[tryt] sealed abstract class TryTInstances0 extends TryTInstances1 { this: TryT.type =>
    implicit def tryTFunctor[F[_]](implicit F0: Functor[F]): Functor[TryT[F, ?]] =
      new TryTFunctor[F] {
        implicit override def F: Functor[F] = F0
      }
  }

  import extractor._

  protected trait TryTFunctor[F[_]] extends Functor[TryT[F, ?]] {
    implicit protected def F: Functor[F]

    override def map[A, B](fa: TryT[F, A])(f: A => B): TryT[F, B] = {
      extractor.apply(F.map(unwrap(fa)) { tryA =>
        tryA.flatMap { a =>
          Try(f(a))
        }
      })
    }
  }

  protected trait TryTBind[F[_]] extends Bind[TryT[F, ?]] with TryTFunctor[F] {
    implicit protected override def F: Monad[F]

    override def bind[A, B](fa: TryT[F, A])(f: A => TryT[F, B]): TryT[F, B] = extractor {
      F.bind[Try[A], Try[B]](unwrap(fa)) {
        case Failure(e) => F.point(Failure(e))
        case Success(value) =>
          unwrap(
            try {
              f(value)
            } catch {
              case NonFatal(e) =>
                extractor.apply[F, B](F.point(Failure(e)))
            }
          )
      }
    }
  }

  protected trait TryTBindRec[F[_]] extends BindRec[TryT[F, ?]] with TryTBind[F] {
    implicit protected def F: Monad[F]
    implicit protected def B: BindRec[F]

    override def tailrecM[A, B](f: A => TryT[F, A \/ B])(a: A): TryT[F, B] = {

      val fTryB: F[Try[B]] = B.tailrecM[A, Try[B]](a =>
        Try(f(a)) match {
          case Success(tryT) =>
            F.map(extractor.unwrap(tryT)) {
              case Failure(e) =>
                \/-(Failure(e))
              case Success(\/-(b)) =>
                \/-(Success(b))
              case Success(left @ -\/(_)) =>
                left
            }
          case Failure(e) =>
            F.point(\/-(Failure(e)))
      })(a)

      extractor(fTryB)
    }
  }

  protected trait TryTMonadError[F[_]] extends MonadError[TryT[F, ?], Throwable] with TryTBind[F] {
    implicit protected override def F: Monad[F]

    override def point[A](a: => A): TryT[F, A] = extractor.apply(F.point(Try(a)))

    override def raiseError[A](e: Throwable): TryT[F, A] = extractor.apply[F, A](F.point(Failure(e)))

    override def handleError[A](fa: TryT[F, A])(f: (Throwable) => TryT[F, A]): TryT[F, A] = extractor {
      F.bind(unwrap(fa)) {
        case Failure(e) =>
          unwrap(
            try {
              f(e)
            } catch {
              case NonFatal(nonFatal) => extractor[F, A](F.point(Failure(nonFatal)))
            }
          )
        case Success(value) => F.point(Success(value))
      }
    }
  }

  protected trait TryTParallelApplicative[F[_]] extends Applicative[Lambda[x => TryT[F, x] @@ Parallel]] {
    implicit protected def F: Applicative[Lambda[x => F[x] @@ Parallel]]
    private type T[A] = TryT[F, A]
    private type P[A] = T[A] @@ Parallel

    override def point[A](a: => A): P[A] =
      Parallel(extractor[F, A] {
        Parallel.unwrap(F.point(Try(a)))
      })

    override def ap[A, B](fa: => P[A])(f: => P[(A) => B]): P[B] = {

      val fTryAP: F[Try[A]] @@ Parallel = Parallel(extractor.unwrap(Parallel.unwrap(fa)))

      val fTryABP: F[Try[A => B]] @@ Parallel = Parallel(extractor.unwrap(Parallel.unwrap(f)))

      import scalaz.syntax.all._

      val fTryBP: F[Try[B]] @@ Parallel =
        F.apply2(fTryAP, fTryABP) { (tryA: Try[A], tryAB: Try[A => B]) =>
          tryA match {
            case Success(a) =>
              tryAB match {
                case Success(ab) =>
                  try {
                    Success(ab(a))
                  } catch {
                    case NonFatal(nonfatal) => Failure(nonfatal)
                  }
                case Failure(failure) => Failure(failure)
              }
            case Failure(failure) =>
              import tryt.TryT.throwableSemigroup
              tryAB match {
                case Success(_) => Failure(failure)
                case Failure(anotherFailure) =>
                  Failure(failure |+| anotherFailure)
              }
          }
        }
      Parallel(extractor.apply(Parallel.unwrap(fTryBP)))
    }
  }

  type TryT[F[_], A] = extractor.TryT[F, A]
}