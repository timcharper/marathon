package mesosphere.marathon.state

import mesosphere.marathon.api.v2.ValidationHelper
import com.wix.accord._
import mesosphere.marathon.test.MarathonSpec
import org.scalatest.Matchers
import org.apache.mesos.Protos.Resource.DiskInfo.Source

class VolumeTest extends MarathonSpec with Matchers {
  import mesosphere.marathon.test.MarathonTestHelper.constraint

  test("validating PersistentVolumeInfo constraints accepts an empty constraint list") {
    val pvi = PersistentVolumeInfo(
      1024,
      constraints = Set.empty)

    validate(pvi).isSuccess shouldBe true
  }

  test("validating PersistentVolumeInfo constraints rejects unsupported fields") {
    val pvi = PersistentVolumeInfo(
      1024,
      `type` = DiskType.Path,
      constraints = Set(constraint("invalid", "LIKE", Some("regex"))))

    val result = validate(pvi)
    result.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe (Set("Unsupported field"))
  }

  test("validating PersistentVolumeInfo constraints rejected for root resources") {
    val result = validate(
      PersistentVolumeInfo(
        1024,
        `type` = DiskType.Root,
        constraints = Set(constraint("path", "LIKE", Some("regex")))))
    result.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe (
      Set("Constraints on root volumes are not supported"))
  }

  test("validating PersistentVolumeInfo constraints rejects bad regex") {
    val pvi = PersistentVolumeInfo(
      1024,
      `type` = DiskType.Path,
      constraints = Set(constraint("path", "LIKE", Some("(bad regex"))))
    val result = validate(pvi)
    result.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe (Set("Invalid regular expression"))
  }

  test("validating PersistentVolumeInfo accepts a valid constraint") {
    val pvi = PersistentVolumeInfo(
      1024,
      `type` = DiskType.Path,
      constraints = Set(constraint("path", "LIKE", Some("valid regex"))))
    val result = validate(pvi)
    result.isSuccess shouldBe true
  }

  test("validating PersistentVolumeInfo maxSize parameter wrt type") {
    val resultRoot = validate(
      PersistentVolumeInfo(1024, `type` = DiskType.Root, maxSize = Some(2048)))
    resultRoot.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(resultRoot).map(_.message) shouldBe (
      Set("Only mount volumes can have maxSize"))

    val resultPath = validate(
      PersistentVolumeInfo(1024, `type` = DiskType.Path, maxSize = Some(2048)))
    resultPath.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(resultPath).map(_.message) shouldBe (
      Set("Only mount volumes can have maxSize"))

    validate(
      PersistentVolumeInfo(1024, `type` = DiskType.Mount, maxSize = Some(2048))).
      isSuccess shouldBe true
  }

  test("validating that DiskSource asMesos converts to an Option Mesos Protobuffer") {
    DiskSource(DiskType.Root, None).asMesos shouldBe None
    val Some(pathDisk) = DiskSource(DiskType.Path, Some("/path/to/folder")).asMesos
    pathDisk.getPath.getRoot shouldBe "/path/to/folder"
    pathDisk.getType shouldBe Source.Type.PATH

    val Some(mountDisk) = DiskSource(DiskType.Mount, Some("/path/to/mount")).asMesos
    mountDisk.getMount.getRoot shouldBe "/path/to/mount"
    mountDisk.getType shouldBe Source.Type.MOUNT

    a[IllegalArgumentException] shouldBe thrownBy {
      DiskSource(DiskType.Root, Some("/path")).asMesos
    }
    a[IllegalArgumentException] shouldBe thrownBy {
      DiskSource(DiskType.Path, None).asMesos
    }
    a[IllegalArgumentException] shouldBe thrownBy {
      DiskSource(DiskType.Mount, None).asMesos
    }
  }

}
