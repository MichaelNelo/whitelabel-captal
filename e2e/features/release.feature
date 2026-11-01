@release
Feature: Releasing the build, and the change data capture relay restarting
  A release moves every deployed location onto the new API image, and onto the new client, which a
  guest's browser loads from the location's own prefix. A paused location is moved too, and stays
  paused, so the new client is what its guests get once it is resumed. The relay carries the
  database's changes to the backoffice and to the locations' APIs; it can restart, and what happens
  after the restart still reaches them.

  @pending
  Scenario: A release moves every location to the new API and client, and keeps a paused location paused
    Given a release location "rel-live" of its own, deployed with an advertiser video
    And a release location "rel-paused" of its own, deployed with an advertiser video
    And the operator pauses "rel-paused"
    When the operator releases the build
    Then the service of "rel-live" runs the image the backoffice deploys
    And the service of "rel-paused" runs the image the backoffice deploys
    And the service of "rel-paused" is still paused
    When Ben opens the portal of "rel-live" on a new phone
    Then Ben's page runs the client its location serves
    And Ben's location serves the newest client release

  Scenario: A playback that was open while the relay restarted still ends, and the file it leaves behind is deleted
    Given the operator's own deployed location "relay" with one advertiser video
    When the relay restarts
    And Ana opens the portal of "relay" on a new phone
    And Ana starts
    And Ana answers the email question with a new address
    Then Ana is watching the first file of "E2E spot" at "relay"
    When Ana pauses the advertiser video part way through
    Then Ana's playback of the advertiser video is held
    When the operator replaces the file of the advertiser video "E2E spot" at "relay"
    Then the old file of "E2E spot" at "relay" is still stored
    When Ana resumes the advertiser video
    And Ana watches the advertiser video to the end
    Then the old file of "E2E spot" at "relay" is deleted
    When Ana answers the video's survey with "Yes"
    Then Ana has internet access
