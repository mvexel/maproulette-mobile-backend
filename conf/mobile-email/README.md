# Guest emails

Copies of the Street Tally sign-up emails. The source is `brand/email/` in the project files (`build.py` there regenerates them); copy any changes here unchanged. `ClaimEmails` in `app/org/maproulette/provider/choice/claim/ClaimMail.scala` fills `{{name}}` placeholders. Values are formatted by the caller and HTML-escaped in the `.html` version. A missing value throws.

| Email | Files | Sent when | Subject |
| --- | --- | --- | --- |
| Link | `claim` | `PUT /api/v2/mobile-guest/email` | Put your {{savedAnswersText}} from {{campaignName}} on the map |
| Reminder | `reminder` | A day after the first link, and in the last five days before `expires_at` | {{reminderSubject}} |
| Expiry | `expiry` | Pending answers expire, before the address is deleted | Your {{campaignName}} answers were removed |
| Sign in again | `reauth` | The publish job stops on `osm_reauth_required` | One more step to put your {{campaignName}} answers on the map |
| Now on the map | `published` | A claim confirmed while writes were paused finishes | Your {{publishedStopsText}} are on the map |

Values, as `GuestEmailService.values` builds them:

- `withOrganizer`: " with Salt Lake Riders", or empty when the campaign has no organizer, so "Thanks for checking bus stops on Thursday, Nov 6." still reads.
- `eventDate`: the campaign's event date once challenge metadata carries one; until then the date of the guest's first answer (long form, campaign time zone).
- `claimUrl`, `deleteUrl`, `stopRemindersUrl`: `<claimOrigin>/claim`, `/claim/delete`, `/claim/stop-reminders`, each with `#t=<token>`. Only the link, reminder and sign-in-again emails carry a token.
- The others (`campaignName`, `firstAnswerDate`, `nounMany`, `savedStopsText`, `savedAnswersText`, `deadline`, `reminderSubject`, `publishedStopsText`, `osmUsername`, `editsUrl`, `notAddedLine`, `privacyUrl`) are described in `brand/email/README.md`.

`GuestEmailSpec` ("render each email as brand/email specifies") checks each email's subject, links and organizer sentence.
