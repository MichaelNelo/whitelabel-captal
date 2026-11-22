package whitelabel.captal.cdc.feed

import zio.test.*

object ChangeFeedQueueSpec extends ZIOSpecDefault:
  def spec =
    suite("ChangeFeedQueue")(
      suite("queue names")(
        test("a service's queues are named under it, within SQS's 80 characters"):
          val name = ChangeFeedQueue.namePrefix("captal-local-feed", "plaza") + ("a" * 32)
          assertTrue(
            name == s"captal-local-feed-plaza-${"a" * 32}",
            ChangeFeedQueue.isQueueOf("captal-local-feed", "plaza", name))
        ,
        test("a long slug is shortened with a digest, and still tells its queues apart"):
          val slug = "the-longest-location-slug-anyone-could-have-written"
          val name = ChangeFeedQueue.namePrefix("captal-prod-feed", slug) + ("0" * 32)
          assertTrue(
            name.length <= ChangeFeedQueue.MaxName,
            ChangeFeedQueue.isQueueOf("captal-prod-feed", slug, name),
            !ChangeFeedQueue.isQueueOf("captal-prod-feed", "the-longest", name)
          )
        ,
        test("one slug's queues are not those of a slug that extends it"):
          val other = ChangeFeedQueue.namePrefix("captal-local-feed", "x-y") + ("b" * 32)
          assertTrue(!ChangeFeedQueue.isQueueOf("captal-local-feed", "x", other))
      ))
end ChangeFeedQueueSpec
