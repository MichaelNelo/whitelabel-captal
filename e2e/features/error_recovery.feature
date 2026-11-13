@error-recovery
Feature: Recovery from an error
  Something that breaks never leaves a guest without internet. An error the server recorded opens
  the way out at once; so does a link into the portal that lost the fingerprint its location asks
  for, which is the venue's setup failing, not the guest. A page opened outside the venue's WiFi
  breaks nothing: there is no phone to let in, so the guest is told to join the network, with
  nothing to retry and no way out. An error in the portal's own code makes the guest wait, as long
  as the effort they have made on that phone asks, and the wait gets shorter when other guests run
  into the same error. How long a new guest waits is the location's to set, and every other wait
  is that one scaled: an identified guest waits three tenths of it, and one whose error other
  guests ran into a tenth. Reloading the error page resumes the wait. Taking the way out grants
  access as the end of the flow does. After the guest has finished, an error gives no way out, but
  staff a code to look it up by.

  Background:
    Given a deployed location "plaza" with an advertiser video and its survey
    And the location "plaza" makes a new guest wait 60 seconds for an error in its portal's code

  Scenario: A server error takes the guest back into the portal at once
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And the portal fails for Ana with a server error
    Then Ana is taken back into the portal within 15 seconds
    And Ana has internet access
    And the venue's controller let Ana's phone in for 60 minutes

  @redirect-parameters
  Scenario: A link that lost its fingerprint takes the guest out through the error page at once
    When Ana opens the portal on a new phone, through a link that lost its fingerprint
    Then Ana is taken through the error page back into the portal within 15 seconds
    And Ana has internet access
    And the venue's controller let Ana's phone in for 60 minutes

  @redirect-parameters
  Scenario: A phone outside the venue's WiFi is told to join it, with no way out
    When Ana opens the portal's address on a new phone, outside the venue's WiFi
    Then Ana is told to join the venue's WiFi, with no retry
    And the venue's controller was not asked anything about Ana's phone

  Scenario: A client error makes a new guest wait the location's maximum, then takes them back in
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And the portal's code throws for Ana
    Then Ana sees the error page
    And Ana is told to wait about 60 seconds
    And Ana is taken back into the portal within 90 seconds
    And Ana has internet access

  Scenario: A guest who has given an email waits less for a client error
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with "ana@example.com"
    And the portal's code throws for Ana
    Then Ana sees the error page
    And Ana is told to wait about 18 seconds

  # The waiting page hears of the other report on a stream the server keeps open. LocalStack's
  # distribution hands a response on only once it ends, which a stream never does, so the guests'
  # streams go through the load balancer. Five minutes, so the countdown is still running when the
  # shortening reaches it.
  @stream-through-load-balancer
  Scenario: Another guest running into the same error shortens the wait
    Given the location "plaza" makes a new guest wait 300 seconds for an error in its portal's code
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And the portal's code throws for Ana
    Then Ana is told to wait about 300 seconds
    When Bruno opens the portal on a new phone
    And Bruno starts
    And Bruno accepts the terms of service
    And Bruno hits the same error on another phone
    Then Ana's countdown reads at most 30 seconds
    And Ana is told that other guests ran into the same problem
    And Ana is taken back into the portal within 40 seconds

  Scenario: Reloading the error page resumes the countdown
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And the portal's code throws for Ana
    Then Ana is told to wait about 60 seconds
    When Ana waits 20 seconds
    And Ana reloads the error page
    Then Ana's countdown reads at most 40 seconds
    And Ana is taken back into the portal within 60 seconds
    And Ana has internet access

  # A new address: the access feature shares this location in a full run, and a guest who already
  # answered its video's survey is offered no video there.
  Scenario: An error after the guest has finished gives staff a code and no way out
    Given a deployed location "unreachable" whose controller cannot be reached
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with a new address
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    And the portal's code throws for Ana
    Then Ana is shown a code for the staff and no way out
