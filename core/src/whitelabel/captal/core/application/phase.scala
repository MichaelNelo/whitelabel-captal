package whitelabel.captal.core.application

enum Phase:
  case IdentificationQuestion
  case AdvertiserVideo
  case AdvertiserQuestion
  case Ready

enum IdentificationSurveyType:
  case Email,
    Profiling,
    Location
