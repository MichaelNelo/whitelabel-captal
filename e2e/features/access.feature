@access
Feature: Guest access
  A phone the venue's network lets in stays in for as long as the location gives it, and is taken
  out again when that time is up, which ends the guest's session. A location whose UniFi is turned
  off never calls its controller, and one whose controller cannot be reached leaves the guest at the
  ready page, waiting, with no error shown to them.

  Scenario: Access ends when its time is up
    Given a deployed location "expiry" whose guests get one minute of access
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana answers the email question with "ana@example.com"
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana has internet access
    And the venue's controller let Ana's phone in for 1 minutes
    And the venue's controller has not yet been asked to take Ana's phone out
    And the venue's controller is asked to take Ana's phone out once its time is up
    And Ana's session is back at the welcome page

  Scenario: A location with UniFi turned off never calls its controller
    Given a deployed location "quiet" with UniFi turned off
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana answers the email question with "ana@example.com"
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana's session stays at the ready page
    And the venue's controller was not asked anything about Ana's phone

  Scenario: A controller that cannot be reached leaves the guest waiting at the ready page
    Given a deployed location "unreachable" whose controller cannot be reached
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana answers the email question with "ana@example.com"
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana's session stays at the ready page
    And Ana sees the spinner of the ready page
    When Ana reloads the page
    Then Ana's session stays at the ready page
    And Ana sees the spinner of the ready page
