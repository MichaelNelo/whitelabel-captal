@pause-resume
Feature: Pausing, resuming and destroying a location
  An operator takes a location out of service without dismantling it. A guest who arrives while it
  is paused finds the maintenance page, and a guest already inside is sent there by their next
  step. Resuming it puts the portal back, so a guest goes through the whole journey again, and a
  guest who was in the middle of it carries on from the same step. Destroying a location removes
  its infrastructure and nothing else: another location keeps serving its guests.

  Scenario: A paused location sends a guest who is mid-journey, and a new guest, to the maintenance page
    Given a location "pause-mid" of its own, deployed with an advertiser video
    When Ana opens the portal on a new phone
    And Ana starts
    And the operator pauses "pause-mid"
    And Ana carries on with the email question, if her portal still shows it
    Then Ana is shown the maintenance page
    When Ben opens the portal on a new phone
    Then Ben is shown the maintenance page

  Scenario: A resumed location serves the whole journey again, and a guest who was mid-journey carries on from the same step
    Given a location "resume" of its own, deployed with an advertiser video
    When Ana opens the portal on a new phone
    And Ana starts
    And the operator pauses "resume"
    And the operator resumes "resume"
    And Ana comes back to the portal on the same phone
    Then Ana's session is at the phase "identification_question"
    When Ana answers the email question with a new address
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana has internet access
    And the venue's controller let Ana's phone in for 60 minutes
    When Ben opens the portal on a new phone
    And Ben starts
    And Ben answers the email question with a new address
    And Ben watches the advertiser video to the end
    And Ben answers the video's survey with "Yes"
    Then Ben has internet access
    And the venue's controller let Ben's phone in for 60 minutes

  Scenario: Destroying one location leaves another one serving its guests
    Given a location "destroyed" of its own, deployed with an advertiser video
    And a location "kept" of its own, deployed with an advertiser video
    When Cy opens the portal of "destroyed" on a new phone
    And the operator destroys "destroyed"
    Then the API of "destroyed" answers nothing for Cy
    When Ana opens the portal of "kept" on a new phone
    And Ana starts
    And Ana answers the email question with a new address
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana has internet access
    And the venue's controller let Ana's phone in for 60 minutes
