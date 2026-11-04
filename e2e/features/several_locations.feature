@several-locations
Feature: Several locations
  Two locations of one deployment, north and south, each with its own brand, texts and video. A
  guest sees the brand and the video of the location they are at, and a guest who has given their
  email at one location is recognised on the same phone at the other: there they are asked the next
  identification question, not the email again. A promo belongs to its location: it plays there and
  nowhere else.

  Background:
    Given an operator builds two locations, north and south, each with its own brand, texts and video, and deploys them

  Scenario: Each portal shows its own brand
    When Ana opens the portal of "north" on a new phone
    Then Ana's portal shows the brand of "north"
    When Ben opens the portal of "south" on a new phone
    Then Ben's portal shows the brand of "south"

  Scenario: Each portal plays its own video, and a promo never plays at another location
    When Ana opens the portal of "north" on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with a new address
    Then Ana is shown the video "North spot"
    When Ben opens the portal of "south" on a new phone
    And Ben starts
    And Ben accepts the terms of service
    And Ben answers the email question with a new address
    Then Ben is shown the video "South promo"

  Scenario: A guest recognised on the same phone at the other location is not asked their email again
    When Ana opens the portal of "north" on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with a new address
    And Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana has internet access
    When Ana comes back to the portal of "south" on the same phone
    And Ana starts
    Then Ana is asked the identification question "Your profiling?"
    When Ana answers the question shown with "Caracas"
    Then Ana is shown the video "South promo"
    When Ana watches the advertiser video to the end
    Then Ana has internet access
    And the venue's controller let Ana's phone in, first for 60 minutes and then for 30 minutes
