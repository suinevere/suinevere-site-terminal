/*----------------------
 | SignOut.tsx
 | Description: Ends the Google session with a CSRF-bearing POST and reloads into a fresh sign-in.
 | Author: suinevere
 | Dependencies: react
 | Globals: N/A
 ----------------------*/

/*----------------------
 | csrfToken
 | Description: Reads the CSRF token Spring writes to a JavaScript-readable cookie.
 | Author: suinevere
 | Dependencies: N/A
 | Globals: N/A
 | Params: N/A
 | Returns: the decoded token, or null when the cookie is absent
 ----------------------*/
function csrfToken(): string | null {
  const entry = document.cookie
    .split('; ')
    .find((candidate) => candidate.startsWith('XSRF-TOKEN='))
  return entry ? decodeURIComponent(entry.slice('XSRF-TOKEN='.length)) : null
}

/*----------------------
 | SignOut
 | Description: Renders the control that ends the session.
 | Author: suinevere
 | Dependencies: csrfToken
 | Globals: N/A
 | Params: N/A
 | Returns: React element for the sign-out button
 ----------------------*/
export default function SignOut() {
  const end = async (): Promise<void> => {
    const token = csrfToken()
    await fetch(`${import.meta.env.BASE_URL}logout`, {
      method: 'POST',
      headers: token ? { 'X-XSRF-TOKEN': token } : {},
    })
    window.location.reload()
  }

  return (
    <button type="button" className="sign-out" onClick={() => void end()}>
      Sign out
    </button>
  )
}
