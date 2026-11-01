@error-recovery
Feature: Recovery from an error
  Something that breaks never leaves a guest without internet. An error the server recorded opens
  the way out at once. An error in the portal's own code makes the guest wait, as long as the
  effort they have made on that phone asks, and the wait gets shorter when other guests run into
  the same error. Reloading the error page resumes the wait. Taking the way out grants access as
  the end of the flow does. After the guest has finished, an error gives no way out, but staff a
  code to look it up by.

  Background:
    Given a deployed location "plaza" with an advertiser video and its survey

  @pending
  Scenario: A server error takes the guest back into the portal at once
    When Ana opens the portal on a new phone
    And Ana starts
    And the portal fails for Ana with a server error
    Then Ana is taken back into the portal within 15 seconds
    And Ana has internet access
    And the venue's controller let Ana's phone in for 60 minutes

  @pending
  Scenario: A client error makes a new guest wait five minutes, then takes them back in
    When Ana opens the portal on a new phone
    And Ana starts
    And the portal's code throws for Ana
    Then Ana sees the error page
    And Ana is told to wait about 300 seconds
    And Ana is taken back into the portal
    And Ana has internet access

  @pending
  Scenario: A guest who has given an email waits less for a client error
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana answers the email question with "ana@example.com"
    And the portal's code throws for Ana
    Then Ana sees the error page
    And Ana is told to wait about 87 seconds

  @pending
  Scenario: Another guest running into the same error shortens the wait
    When Ana opens the portal on a new phone
    And Ana starts
    And the portal's code throws for Ana
    Then Ana is told to wait about 300 seconds
    When Bruno opens the portal on a new phone
    And Bruno starts
    And Bruno hits the same error on another phone
    Then Ana's countdown reads at most 30 seconds
    And Ana is told that other guests ran into the same problem
    And Ana is taken back into the portal within 40 seconds

  @pending
  Scenario: Reloading the error page resumes the countdown
    When Ana opens the portal on a new phone
    And Ana starts
    And the portal's code throws for Ana
    Then Ana is told to wait about 300 seconds
    When Ana waits 60 seconds
    And Ana reloads the error page
    Then Ana's countdown reads at most 240 seconds
    And Ana is taken back into the portal
    And Ana has internet access

  @pending
  Scenario: An error after the guest has finished gives staff a code and no way out
    Given a deployed location "unreachable" whose controller cannot be reached
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana answers the email question with "ana@example.com"
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    And the portal's code throws for Ana
    Then Ana is shown a code for the staff and no way out
