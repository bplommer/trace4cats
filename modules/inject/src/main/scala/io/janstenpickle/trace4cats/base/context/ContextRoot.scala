package io.janstenpickle.trace4cats.base.context

import cats.Monad
import cats.data.Kleisli

trait ContextRoot extends Serializable

object ContextRoot {
  implicit def kleisliInstance[F[_]: Monad, R]: Provide[F, Kleisli[F, R, *], R] =
    new Provide[F, Kleisli[F, R, *], R] {
      def Low: Monad[F] = implicitly
      def F: Monad[Kleisli[F, R, *]] = implicitly

      def ask[R1 >: R]: Kleisli[F, R, R1] = Kleisli.ask
      def local[A](fa: Kleisli[F, R, A])(f: R => R): Kleisli[F, R, A] = fa.local(f)
      def lift[A](fa: F[A]): Kleisli[F, R, A] = Kleisli.liftF(fa)
      def provide[A](fa: Kleisli[F, R, A])(r: R): F[A] = fa.run(r)
    }
}
