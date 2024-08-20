/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.service.deploy.worker.storage

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.channels.FileChannel
import java.util
import io.netty.buffer.{ByteBufUtil, CompositeByteBuf}
import org.apache.hadoop.fs.Path
import org.apache.celeborn.common.protocol.StorageInfo.Type
import org.apache.celeborn.reflect.{DynClasses, DynMethods}

abstract private[worker] class FlushTask(
    val buffer: CompositeByteBuf,
    val notifier: FlushNotifier,
    val keepBuffer: Boolean) {
  def flush(): Unit
}

private[worker] class LocalFlushTask(
    buffer: CompositeByteBuf,
    fileChannel: FileChannel,
    notifier: FlushNotifier,
    keepBuffer: Boolean) extends FlushTask(buffer, notifier, keepBuffer) {
  override def flush(): Unit = {
    val buffers = buffer.nioBuffers()
    for (buffer <- buffers) {
      while (buffer.hasRemaining) {
        fileChannel.write(buffer)
      }
    }
  }
}

private[worker] class HdfsFlushTask(
    buffer: CompositeByteBuf,
    val path: Path,
    notifier: FlushNotifier,
    keepBuffer: Boolean) extends FlushTask(buffer, notifier, keepBuffer) {
  override def flush(): Unit = {
    val hadoopFs = StorageManager.hadoopFs.get(Type.HDFS)
    val hdfsStream = hadoopFs.append(path, 256 * 1024)
    hdfsStream.write(ByteBufUtil.getBytes(buffer))
    hdfsStream.close()
  }


}

private[worker] class S3FlushTask(
    buffer: CompositeByteBuf,
    val path: Path,
    notifier: FlushNotifier,
    keepBuffer: Boolean, s3Flusher: S3Flusher) extends FlushTask(buffer, notifier, keepBuffer) {

  val hadoopFs = s3Flusher.hadoopFs

  val bucketName = s3Flusher.bucketName

  var s3Client = s3Flusher.s3Client

  var uploadId = s3Flusher.uploadId

  var partNumber = s3Flusher.partNumber

  val partETags = s3Flusher.partETags

  override def flush(): Unit = {
    val bytes = ByteBufUtil.getBytes(buffer)
    var bytesRead = 0
    val partSize = 50 * 1024 * 1024

    while (bytesRead < bytes.length) {
      val currentPartSize = Math.min(partSize, bytes.length - bytesRead)
      val inputStream = new ByteArrayInputStream(bytes, bytesRead, currentPartSize)
      val uploadRequest = DynClasses
        .builder()
        .impl("com.amazonaws.services.s3.model.UploadPartRequest")
        .build()
        .getDeclaredConstructor()
        .newInstance()

      val uploadRequestWithBucket = DynMethods
        .builder("withBucketName")
        .impl(uploadRequest.getClass, classOf[String])
        .build()
        .invoke(uploadRequest, bucketName)

      val uploadRequestWithKey = DynMethods
        .builder("withKey")
        .impl(uploadRequestWithBucket.getClass, classOf[String])
        .build()
        .invoke(uploadRequestWithBucket, path.toString)

      val uploadRequestWithUploadId = DynMethods
        .builder("withUploadId")
        .impl(uploadRequestWithKey.getClass, classOf[String])
        .build()
        .invoke(uploadRequestWithKey, uploadId)

      val uploadRequestWithPartNumber = DynMethods
        .builder("withPartNumber")
        .impl(uploadRequestWithUploadId.getClass, classOf[Int])
        .build()
        .invoke(uploadRequestWithUploadId, partNumber)

      val uploadRequestWithInputStream = DynMethods
        .builder("withInputStream")
        .impl(uploadRequestWithPartNumber.getClass, classOf[InputStream])
        .build()
        .invoke(uploadRequestWithPartNumber, inputStream)

      val uploadRequestWithPartSize = DynMethods
        .builder("withPartSize")
        .impl(uploadRequestWithInputStream.getClass, classOf[Long])
        .build()
        .invoke(uploadRequestWithInputStream, currentPartSize)

      val uploadResult = DynMethods
        .builder("uploadPart")
        .impl(s3Client.getClass, uploadRequestWithPartSize.getClass)
        .build()
        .invoke(s3Client, uploadRequestWithPartSize)

      val partETag = DynMethods.builder("getPartETag")
        .impl(uploadResult.getClass)
        .build()
        .invoke(uploadResult)

      partETags.add(partETag)

      bytesRead += currentPartSize
      partNumber += 1
    }
  }


}
