@first-visit
Feature: A guest's first visit
  A guest the portal has never seen identifies with their email, watches an advertiser's video,
  answers the question asked after it, and reaches the end of the journey. These locations make
  no UniFi calls, so the end is where access would be granted: the session stays ready for it.

  Background:
    Given a deployed location "plaza" with an advertiser video and its survey

  Scenario: A new guest goes from the welcome page to the end of the journey
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana answers the email question with "ana@example.com"
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana's journey ends where her access would be granted
