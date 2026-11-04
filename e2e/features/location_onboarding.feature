@onboarding
Feature: Building a location
  An operator builds a location through the backoffice (its stylesheet, its icon, its texts in
  Spanish and English, an advertiser video) and deploys it. A guest who opens its portal sees the
  location as it was built: its brand, its texts in the language the guest has chosen, its video.
  The operator sees it before ever deploying it, every screen at once, in the backoffice's preview.

  Background:
    Given an operator builds the location "brasa" with its own styles, icon, texts and advertiser video, and deploys it

  Scenario: A guest sees the location's stylesheet and icon
    When Ana opens the portal on a new phone
    Then Ana's portal uses the location's stylesheet
    And Ana's portal shows the location's icon

  Scenario Outline: The operator previews the <screen> screen of a location not yet deployed
    Given an operator builds the location "boceto" with its own styles, icon, texts and advertiser video, without deploying it
    When the operator previews the <screen> screen of the location "boceto"
    Then the preview shows what a guest sees on that screen
    And the preview uses the location's stylesheet
    And the location "boceto" has no portal yet

    Examples:
      | screen   |
      | welcome  |
      | question |
      | video    |
      | survey   |
      | ready    |
      | access   |
      | error    |

  Scenario: The preview of a location not yet deployed reads the location's own texts
    Given an operator builds the location "boceto" with its own styles, icon, texts and advertiser video, without deploying it
    When the operator previews the welcome screen of the location "boceto"
    Then the preview reads "Bienvenidos a Boceto" as the welcome title

  Scenario: A guest on a phone set to Spanish reads the Spanish texts
    When Ana opens the portal on a new phone set to "es-ES"
    Then Ana reads "Bienvenidos a Brasa" as the welcome title

  Scenario: A guest on a phone set to English is still greeted in Spanish
    When Ana opens the portal on a new phone set to "en-US"
    Then Ana reads "Bienvenidos a Brasa" as the welcome title

  Scenario: A guest who chooses English keeps it when they come back on the same phone
    When Ana opens the portal on a new phone
    And Ana chooses "en" as the language
    Then Ana reads "Welcome to Brasa" as the welcome title
    When Ana comes back to the portal on the same phone
    Then Ana reads "Welcome to Brasa" as the welcome title

  Scenario: A new guest gives their email, watches the location's video and gets access
    When Ana opens the portal on a new phone
    And Ana starts
    And Ana accepts the terms of service
    And Ana answers the email question with a new address
    Then Ana is shown the video "Brasa spot"
    When Ana watches the advertiser video to the end
    And Ana answers the video's survey with "Yes"
    Then Ana has internet access
    And the venue's controller let Ana's phone in for 60 minutes
