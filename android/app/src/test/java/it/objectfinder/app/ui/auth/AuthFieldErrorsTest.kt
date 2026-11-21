package it.objectfinder.app.ui.auth

import it.objectfinder.app.data.remote.isLocalizedServerMessage
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gli errori mostrati sotto un campo devono restare identici alle traduzioni dei messaggi del server. */
class AuthFieldErrorsTest {
    @Test fun fieldErrorsMatchServerTranslations() {
        (EMAIL_ERRORS + PASSWORD_ERRORS).forEach {
            assertTrue("\"$it\" non è più una traduzione in SupabaseHttp", isLocalizedServerMessage(it))
        }
    }
}
