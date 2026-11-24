package whitelabel.captal.mcp.tools

import whitelabel.captal.core.application.queries.{
  AccessPointsOfQuery,
  AccessPointsQuery,
  AccessPointsReport,
  AdvertiserReport,
  AssetCount,
  AssetsOfQuery,
  ControllerReport,
  ControllersQuery,
  DispatchQuery,
  DispatchRoute,
  IconOfQuery,
  KnownAccessPoint,
  ListAdvertisersQuery,
  ListLocationsQuery,
  ListSurveysQuery,
  LocationPreview,
  LocationReadinessQuery,
  LocationSummary,
  OpenPlaybackReport,
  OpenPlaybacksQuery,
  PendingUploadReport,
  PendingUploadsQuery,
  PreviewLocationQuery,
  PublishedDocument,
  Query,
  ReadinessReport,
  RecoveryPolicyOfQuery,
  RecoveryPolicyReport,
  RunReport,
  RunStatusQuery,
  StylesOfQuery,
  SurveyOfQuery,
  SurveyReport,
  SurveySummaryReport,
  TermsInForceQuery,
  TermsReport,
  TranslationCoverageQuery,
  TranslationCoverageReport,
  UploadUrlQuery,
  VideoOfQuery,
  VideoReport,
  VideosOfQuery,
  WaitingUpload
}
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.queries.{
  AssetsOf as AssetsOfQuill,
  DispatchRoutes,
  ListAdvertisers as ListAdvertisersQuill,
  LocationReadiness as LocationReadinessQuill,
  Locations,
  Playbacks,
  PublishedDocuments,
  RecoveryPolicyOf as RecoveryPolicyOfQuill,
  RunStatus as RunStatusQuill,
  Surveys,
  TermsInForce as TermsInForceQuill,
  TranslationCoverage as TranslationCoverageQuill,
  Videos
}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

/** The backoffice's queries, built once, as [[ContentFlows]] builds its flows: a tool finds the one
  * it asks in the environment ([[Adapters.reads]]). Those that read only the database are apart
  * from those that also read the assets bucket, for a caller with no bucket to give them.
  */
object Queries:
  type ListLocations = Query.Aux[Task, ListLocationsQuery, List[LocationSummary]]
  type AccessPointsOf = Query.Aux[Task, AccessPointsOfQuery, AccessPointsReport]
  type ListAdvertisers = Query.Aux[Task, ListAdvertisersQuery, List[AdvertiserReport]]
  type RecoveryPolicyOf = Query.Aux[Task, RecoveryPolicyOfQuery, RecoveryPolicyReport]
  type TermsInForce = Query.Aux[Task, TermsInForceQuery, TermsReport]
  type TranslationCoverage = Query.Aux[Task, TranslationCoverageQuery, TranslationCoverageReport]
  type ListSurveys = Query.Aux[Task, ListSurveysQuery, List[SurveySummaryReport]]
  type SurveyOf = Query.Aux[Task, SurveyOfQuery, SurveyReport]
  type VideosOf = Query.Aux[Task, VideosOfQuery, List[VideoReport]]
  type PendingUploads = Query.Aux[Task, PendingUploadsQuery, List[PendingUploadReport]]
  type RunStatus = Query.Aux[Task, RunStatusQuery, RunReport]
  type VideoOf = Query.Aux[Task, VideoOfQuery, VideoReport]
  type OpenPlaybacks = Query.Aux[Task, OpenPlaybacksQuery, List[OpenPlaybackReport]]
  type Dispatch = Query.Aux[Task, DispatchQuery, List[DispatchRoute]]
  type Controllers = Query.Aux[Task, ControllersQuery, List[ControllerReport]]
  type KnownAccessPoints = Query.Aux[Task, AccessPointsQuery, List[KnownAccessPoint]]

  type PreviewLocation = Query.Aux[Task, PreviewLocationQuery, LocationPreview]
  type StylesOf = Query.Aux[Task, StylesOfQuery, PublishedDocument]
  type IconOf = Query.Aux[Task, IconOfQuery, PublishedDocument]
  type UploadUrl = Query.Aux[Task, UploadUrlQuery, WaitingUpload]
  type LocationReadiness = Query.Aux[Task, LocationReadinessQuery, ReadinessReport]
  type AssetsOf = Query.Aux[Task, AssetsOfQuery, AssetCount]

  /** The ones that read the database alone. */
  type Database =
    ListLocations & AccessPointsOf & ListAdvertisers & RecoveryPolicyOf & TermsInForce &
      TranslationCoverage & ListSurveys & SurveyOf & VideosOf & PendingUploads & RunStatus &
      VideoOf & OpenPlaybacks & Dispatch & Controllers & KnownAccessPoints

  /** The ones that read the assets bucket too. */
  type Assets = PreviewLocation & StylesOf & IconOf & UploadUrl & LocationReadiness & AssetsOf

  type All = Database & Assets

  def database(quill: QuillSqlite): ZEnvironment[Database] = ZEnvironment
    .empty
    .add[ListLocations](Locations.list(quill))
    .add[AccessPointsOf](Locations.accessPoints(quill))
    .add[ListAdvertisers](ListAdvertisersQuill(quill))
    .add[RecoveryPolicyOf](RecoveryPolicyOfQuill(quill))
    .add[TermsInForce](TermsInForceQuill(quill))
    .add[TranslationCoverage](TranslationCoverageQuill(quill))
    .add[ListSurveys](Surveys.list(quill))
    .add[SurveyOf](Surveys.of(quill))
    .add[VideosOf](Videos.of(quill))
    .add[PendingUploads](Videos.pending(quill))
    .add[RunStatus](RunStatusQuill(quill))
    .add[VideoOf](Videos.one(quill))
    .add[OpenPlaybacks](Playbacks.open(quill))
    .add[Dispatch](DispatchRoutes(quill))
    .add[Controllers](Locations.controllers(quill))
    .add[KnownAccessPoints](Locations.known(quill))

  def assets(quill: QuillSqlite, store: AssetStore): ZEnvironment[Assets] = ZEnvironment
    .empty
    .add[PreviewLocation](Locations.preview(quill, store))
    .add[StylesOf](PublishedDocuments.styles(quill, store))
    .add[IconOf](PublishedDocuments.icon(quill, store))
    .add[UploadUrl](Videos.uploadUrl(quill, store))
    .add[LocationReadiness](LocationReadinessQuill(quill, store))
    .add[AssetsOf](AssetsOfQuill(store))

  /** Every one, as `Main` provides them. */
  val layer: ZLayer[QuillSqlite & AssetStore, Nothing, All] = ZLayer.fromZIOEnvironment:
    for
      quill <- ZIO.service[QuillSqlite]
      store <- ZIO.service[AssetStore]
    yield database(quill) ++ assets(quill, store)
end Queries
