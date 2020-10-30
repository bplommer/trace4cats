package io.janstenpickle.trace4cats.avro.kafka

import java.io.ByteArrayOutputStream

import cats.ApplicativeError
import cats.data.NonEmptyList
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Resource, Sync}
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.show._
import fs2.kafka._
import io.chrisdavenport.log4cats.Logger
import io.janstenpickle.trace4cats.kernel.SpanExporter
import io.janstenpickle.trace4cats.model.{Batch, TraceId}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.EncoderFactory

object AvroKafkaSpanExporter {
  implicit def keySerializer[F[_]: Sync]: Serializer[F, TraceId] = Serializer.string[F].contramap[TraceId](_.show)
  def valueSerializer[F[_]: Sync](schema: Schema): Serializer[F, KafkaSpan] = Serializer.instance[F, KafkaSpan] {
    (_, _, span) =>
      for {
        record <- KafkaSpan.kafkaSpanCodec.encode(span).leftMap(_.throwable).liftTo[F]
        ba <- Resource
          .make(
            Sync[F]
              .delay {
                val writer = new GenericDatumWriter[Any](schema)
                val out = new ByteArrayOutputStream

                val encoder = EncoderFactory.get.binaryEncoder(out, null)

                (writer, out, encoder)
              }
          ) {
            case (_, out, _) =>
              Sync[F].delay(out.close())
          }
          .use {
            case (writer, out, encoder) =>
              Sync[F].delay {
                writer.write(record, encoder)
                encoder.flush()
                out.toByteArray
              }
          }
      } yield ba
  }

  def apply[F[_]: ConcurrentEffect: ContextShift: Logger](
    blocker: Blocker,
    bootStrapServers: NonEmptyList[String],
    topic: String,
    modifySettings: ProducerSettings[F, TraceId, KafkaSpan] => ProducerSettings[F, TraceId, KafkaSpan] =
      (x: ProducerSettings[F, TraceId, KafkaSpan]) => x
  ): Resource[F, SpanExporter[F]] =
    Resource.liftF(KafkaSpan.kafkaSpanCodec.schema.leftMap(_.throwable).map(valueSerializer[F]).liftTo[F]).flatMap {
      implicit ser =>
        producerResource[F]
          .using(
            modifySettings(
              ProducerSettings[F, TraceId, KafkaSpan]
                .withBlocker(blocker)
                .withBootstrapServers(bootStrapServers.mkString_(","))
            )
          )
          .map(fromProducer[F](_, topic))
    }

  def fromProducer[F[_]: ApplicativeError[*[_], Throwable]: Logger](
    producer: KafkaProducer[F, TraceId, KafkaSpan],
    topic: String
  ): SpanExporter[F] = new SpanExporter[F] {
    override def exportBatch(batch: Batch): F[Unit] =
      producer
        .produce(ProducerRecords[List, TraceId, KafkaSpan](batch.spans.map { span =>
          ProducerRecord(topic, span.context.traceId, KafkaSpan(batch.process, span))
        }))
        .map(_.onError {
          case e =>
            Logger[F].warn(e)("Failed to export record batch to Kafka")
        })
        .void
  }
}