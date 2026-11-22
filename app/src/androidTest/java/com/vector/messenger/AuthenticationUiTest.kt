package com.vector.messenger

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.vector.core.auth.*
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.AuthenticationContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AuthenticationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneSubmissionIsNormalizedAndInputClears() {
        var submitted: AuthAction? = null
        compose.setContent { DefaultTheme { AuthenticationContent(AuthState(AuthStep.Phone), { submitted = it }, {}, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("+1 202-555-0123")
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertTrue(submitted is AuthAction.Phone); assertTrue((submitted as AuthAction.Phone).value == "+12025550123") }
        compose.onNode(hasSetTextAction()).assertTextEquals("Your phone number", "")
    }
    @Test fun passwordIsMaskedAndClearedBeforeRequestCompletes() {
        var submitted = false
        compose.setContent { DefaultTheme { AuthenticationContent(AuthState(AuthStep.Password("sample hint")), { submitted = it is AuthAction.Password }, {}, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("fake test password")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists()
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertTrue(submitted) }
        compose.onNode(hasSetTextAction()).assertTextEquals("Two-step verification", "")
    }
    @Test fun accountStateDoesNotReplaceTheChatInterfaceWithRealMessages() {
        compose.setContent { DefaultTheme { AuthenticationContent(AuthState(AuthStep.Ready), {}, {}, {}) } }
        compose.onNodeWithText("Signed in to Telegram").assertIsDisplayed()
        compose.onNodeWithText("Log out").performClick()
        compose.onNodeWithText("Log out of Telegram?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Signed in to Telegram").assertIsDisplayed()
    }
}
