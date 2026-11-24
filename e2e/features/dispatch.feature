@dispatch
Feature: The access point sends its guests where its location says
  UniFi sends a phone that joins the venue's WiFi to the external portal server, the load
  balancer's address, with the access point's MAC. The backoffice keeps a rule there for each access
  point, written as its location is deployed, paused, resumed or destroyed and as its access points
  change. Every feature's guests arrive through their location's rule; these are the other two
  answers it gives.

  Scenario: An access point with an address of its own sends its guests there
    Given a deployed location "outlet" whose access point sends its guests to "https://venue.example/landing?src=wifi"
    When a phone joins through the access point of "outlet"
    Then the load balancer sends it to "https://venue.example/landing?src=wifi"

  Scenario: An access point no location has turns its guests away
    When a phone joins through the access point "02:00:00:00:00:01", which no location has
    Then the load balancer answers 404
