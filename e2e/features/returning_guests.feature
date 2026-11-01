@returning-guests
Feature: Returning guests
  The identification questions are asked one per visit, in order: the email first, then the
  profiling questions, then the location questions, each answer followed by the video. A guest
  keeps what they answered: the same phone is asked the next question when it comes back, and a
  new phone that gives a known email is taken for that guest, so on its next visit it is asked what
  the guest has not answered yet. A guest who has answered all of them goes straight to the video.

  Background:
    Given a deployed location "returning" with a promo of the venue, whose guests get one minute of access

  Scenario: The same phone, back after its access ran out, is asked the profiling question instead of the email
    Given Ana has had a first visit
    When Ana waits for the access to run out
    And Ana comes back to the portal on the same phone
    And Ana starts
    Then Ana is asked the profiling question

  Scenario: A new phone that gives a known email is recognised, and is asked what the guest has not answered yet
    Given Ana has had a first visit
    When Ana waits for the access to run out
    And Ana comes back to the portal on the same phone
    And Ana starts
    And Ana answers the question shown with "Blue"
    And Ana watches the advertiser video to the end
    And Ana has internet access
    When Ben opens the portal on a new phone
    And Ben starts
    And Ben answers the email question with the address of Ana
    Then Ben is shown the video straight away
    When Ben watches the advertiser video to the end
    And Ben has internet access
    And Ben waits for the access to run out
    And Ben comes back to the portal on the same phone
    And Ben starts
    Then Ben is asked the location question

  Scenario: A guest who has answered every identification question goes straight to the video
    Given Ana has had a first visit
    When Ana waits for the access to run out
    And Ana comes back to the portal on the same phone
    And Ana starts
    And Ana answers the question shown with "Blue"
    And Ana watches the advertiser video to the end
    And Ana has internet access
    When Ana waits for the access to run out
    And Ana comes back to the portal on the same phone
    And Ana starts
    And Ana answers the question shown with "Lisbon"
    And Ana watches the advertiser video to the end
    And Ana has internet access
    When Ana waits for the access to run out
    And Ana comes back to the portal on the same phone
    And Ana starts
    Then Ana is shown the video straight away
