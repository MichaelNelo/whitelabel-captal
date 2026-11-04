@live-content
Feature: Changing a deployed location while guests use it
  An operator changes what a deployed location shows (the questions of an advertiser's survey, its
  videos and their files, its translations) and the guests who arrive afterwards get the change. A
  guest already watching a file keeps the file they were given until they finish it, and the old
  file goes once nobody plays it. None of these changes needs a deploy.

  Scenario: A question the operator retires is not asked to the next guest
    Given the operator's own deployed location "questions" whose advertiser video asks two questions
    When the operator retires the survey question "Did you like it?" of the advertiser video at "questions"
    And Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with "ana.live@example.com"
    And Ana watches the advertiser video to the end
    Then Ana is shown the survey question "Would you recommend it?"
    And Ana is not shown the survey question "Did you like it?"
    When Ana answers the video's survey with "Yes"
    Then Ana has internet access

  Scenario: A guest already watching the old file finishes it, the next guest gets the new one, and the old file goes once nobody plays it
    Given the operator's own deployed location "replacement" with one advertiser video
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with "ana.live@example.com"
    Then Ana is watching the first file of "E2E spot" at "replacement"
    When the operator replaces the file of the advertiser video "E2E spot" at "replacement"
    Then the old file of "E2E spot" at "replacement" is still stored
    When Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana has internet access
    When Ben opens the portal on a new phone
    And Ben starts
    And Ben accepts the terms of service
    And Ben answers the email question with "ben.live@example.com"
    Then Ben is given the new file of "E2E spot" at "replacement"
    And the old file of "E2E spot" at "replacement" is deleted

  Scenario: A video the operator takes out of rotation is not shown to the next guest
    Given the operator's own deployed location "rotation" with two advertiser videos
    When the operator takes the advertiser video "E2E spot" out of rotation at "rotation"
    And Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with "ana.live@example.com"
    And Ana watches the advertiser video to the end
    Then Ana is shown the survey question "Did you like the second one?"
    And Ana is not shown the survey question "Did you like it?"
    When Ana answers the video's survey with "Yes"
    Then Ana has internet access

  Scenario: A translation the operator sets reaches the next guest without a deploy
    Given the operator's own deployed location "wording" with one advertiser video
    When the operator sets the Spanish text of "welcome.button.start" to "Empezar ahora" at "wording"
    And Ana opens the portal on a new phone
    Then Ana sees the welcome button "Empezar ahora"

  Scenario: A guest who walks away mid-video keeps the file only until its playback expires
    Given the operator's own deployed location "abandoned" with one advertiser video
    And the location "abandoned" closes a playback its page never ended 120 seconds after its file ends
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with "ana.live@example.com"
    Then Ana is watching the first file of "E2E spot" at "abandoned"
    When Ana leaves the WiFi while the advertiser video plays
    And the operator replaces the file of the advertiser video "E2E spot" at "abandoned"
    Then the old file of "E2E spot" at "abandoned" is still stored
    And the old file of "E2E spot" at "abandoned" is deleted once its playback has expired
