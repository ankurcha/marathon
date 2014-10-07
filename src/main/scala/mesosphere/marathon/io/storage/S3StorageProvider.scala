package mesosphere.marathon.io.storage

import java.io.{FileOutputStream, InputStream, OutputStream}
import java.nio.file.Files

import awscala.s3._
import com.amazonaws.services.s3.model.CopyObjectResult


case class S3StorageItem(s3: S3, bucket: Bucket, key: String) extends StorageItem {

  /**
   * Store this item with given output stream function.
   */
  override def store(fn: (OutputStream) => Unit): StorageItem = {
    val path = Files.createTempFile("s3temp", "temp")
    try {
      val tempFile = path.toFile
      using(new FileOutputStream(tempFile)) { fn }
      val result = s3.put(bucket, key, tempFile)
      assert(result != null, s"S3 file system could store $path")
      this
    } finally {
      Files.deleteIfExists(path)
    }
  }

  /**
   * Move this item inside the storage system.
   * @param newPath the new path of this item
   * @return the moved storage item.
   */
  override def moveTo(newPath: String): StorageItem = {
    val src: S3Object = s3.get(bucket, key).get
    val to = s3.get(bucket, newPath)
    var result: Option[CopyObjectResult] = None
    try {
      if (to.isDefined) {
        s3.delete(to.get)
      }
      result = Option(s3.copyObject(bucket.name, key, bucket.name, newPath))
      s3.delete(src)
    } catch {
      case _: Throwable => Unit
    }
    assert(result.isDefined, s"S3 file system could not move $path to $newPath")
    new S3StorageItem(s3, bucket, newPath)
  }

  /**
   * Returns the length of the item.
   */
  override def length: Long = s3.get(bucket, key) match {
    case Some(obj) => obj.getObjectMetadata.getContentLength
    case None => 0L
  }

  /**
   * The provider specific url of this item.
   */
  override def url: String = s"s3://${bucket.name}$key"

  /**
   * Returns the time that the item was last modified.
   */
  override def lastModified: Long = s3.get(bucket, key) match {
    case Some(obj) => obj.getObjectMetadata.getLastModified.getTime
    case None => 0L
  }

  /**
   * Delete this item.
   */
  override def delete(): Unit = s3.get(bucket, key) match {
    case Some(obj) => s3.delete(obj)
    case None => Unit
  }

  /**
   * Indicates, whether the item exists in the file system or not.
   */
  override def exists: Boolean = s3.get(bucket, key).isEmpty

  /**
   * Read the item and give the input stream.
   */
  override def inputStream(): InputStream = s3.get(bucket, key).get.getObjectContent

  /**
   * The provider independent path of the item.
   */
  override def path: String = s"s3://${bucket.name}$key"
}

class S3StorageProvider(access_key: String, secret_key: String, bucketName: String, prefix: String) extends StorageProvider {
  implicit val s3 = S3(access_key, secret_key)
  val bucket: Bucket = s3.createBucket(bucketName)
  val base: String = prefix

  override def item(path: String): StorageItem = new S3StorageItem(s3, bucket, if (path.startsWith("/")) s"$base$path" else s"$base/$path")
}