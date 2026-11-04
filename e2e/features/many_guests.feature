@many-guests
Feature: Many guests at one location
  Several guests can be at a location at the same time, each on a phone of their own, and each of
  them gets internet access: the venue's controller is asked to let each phone in, once per visit.
  The venue's promos rotate per guest: a guest is shown the one they have watched least, in rotation
  order, so a guest's visits go through the promos in turn, whoever else is at the location.

  Scenario: Five guests at one location each get internet access, and the controller lets each phone in once
    Given a deployed location "plaza" with an advertiser video and its survey
    When the crowd opens the portal on new phones
    And the crowd starts
    And the crowd accepts the terms of service
    And the crowd answers the email question with an address of their own
    And the crowd watches the video to the end
    And the crowd answers the video's survey with "Yes"
    Then the crowd has internet access
    And the venue's controller let each of the crowd's phones in once for 60 minutes

  Scenario: Five guests at a location with three promos are each shown them in rotation
    Given a deployed location "promos" whose venue has three promos, in rotation order
    When the crowd opens the portal on new phones
    And the crowd starts
    And the crowd accepts the terms of service
    And the crowd answers the email question with an address of their own
    Then each of the crowd is shown the promo "Alpha"
    When the crowd watches the video to the end
    And the crowd has internet access
    And the crowd waits for its access to run out
    And the crowd comes back to the portal on the same phones
    And the crowd starts
    And the crowd answers the question shown with "Blue"
    Then each of the crowd is shown the promo "Bravo"
    When the crowd watches the video to the end
    And the crowd has internet access
    And the crowd waits for its access to run out
    And the crowd comes back to the portal on the same phones
    And the crowd starts
    And the crowd answers the question shown with "Lisbon"
    Then each of the crowd is shown the promo "Charlie"
    When the crowd watches the video to the end
    And the crowd has internet access
