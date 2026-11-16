# Local feedback visual fixtures

Run `node qa/feedback-preview/build.mjs`. Use the supported preview mechanism to
serve `qa/feedback-preview/dist`; no production deploy is involved. The fixture
imports real UI components and a fake client. It does not use credentials or
call game, auth, storage or statistics APIs.

`launcher.html?width=390&height=844&mode=bidding` runs the actual nine-step UI;
practice inputs and three plays only update local synthetic fixture state.

Other parameters:
- `mode=play&play=0`: contract explanation (step 4), then follow-suit play.
- `mode=play&play=1`: public first-trick replay (step 6), then second play.
- `mode=play&play=2`: trump play (step 8).
- `mode=play&play=3`: final completion (step 9).
- `mode=table&holder=0` through `holder=3`: bidder badge at each seat.
- `long=1`: long names for the table fixture.

Review 320×568, 390×844, 430×932 and desktop. Check each step's target and text,
rounded corners, popup scroll, previous/next and final exit, repeated review,
selected card versus expand-hand button, all bidder positions and long names.
These are layout fixtures, not evidence of backend rule/storage correctness.
