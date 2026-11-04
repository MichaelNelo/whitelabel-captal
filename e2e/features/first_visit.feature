@first-visit
Feature: A guest's first visit
  A guest the portal has never seen identifies with their email, watches an advertiser's video,
  answers the question asked after it, and leaves with internet access, granted by the venue's
  network for as long as the location gives it.

  Background:
    Given a deployed location "plaza" with an advertiser video and its survey

  Scenario: A new guest goes from the welcome page to internet access
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with "ana@example.com"
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana has internet access
    And the venue's controller let Ana's phone in for 60 minutes
