import { UserManager } from "oidc-client-ts";

// The provider redirects the silent-renew iframe here; hand the response URL to the app in the parent window,
// which redeems the code. Only the callback runs, so the settings just have to be well-formed.
void new UserManager({ authority: window.location.origin, client_id: "silent-renew", redirect_uri: window.location.href })
  .signinSilentCallback();
