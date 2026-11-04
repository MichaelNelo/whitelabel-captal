@terms
Feature: Terms of service
  Before the flow begins, a guest accepts the terms of service, the same for every location. A phone
  the portal knows nothing about is asked; declining leaves it at the welcome, told that the terms
  have to be accepted to connect. A phone that accepted the version in force is not asked again, at
  that location or another, until a new version is published.

  Background:
    Given a deployed location "terms" with an advertiser video and its survey
    And a deployed location "terms-b" with an advertiser video and its survey

  Scenario: Declining the terms leaves the guest at the welcome, and accepting them starts
    When Ana opens the portal of "terms" on a new phone
    And Ana declines the terms of service
    Then Ana is told the terms of service have to be accepted
    When Ana starts
    And Ana answers the email question with a new address

  Scenario: A phone that accepted the terms is not asked again at another location
    When Ana opens the portal of "terms" on a new phone
    And Ana starts
    And Ana answers the email question with a new address
    And Ana comes back to the portal of "terms-b" on the same phone
    Then Ana starts without being asked for the terms of service

  Scenario: A new version of the terms is asked for again
    When Ana opens the portal of "terms" on a new phone
    And Ana starts
    And Ana answers the email question with a new address
    And the operator publishes a new version of the terms of service
    And Ana comes back to the portal of "terms-b" on the same phone
    Then Ana is asked to accept the terms of service
