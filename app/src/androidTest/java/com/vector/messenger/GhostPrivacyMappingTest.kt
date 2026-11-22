package com.vector.messenger

import com.vector.core.ghost.*
import com.vector.messenger.telegram.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GhostPrivacyMappingTest {
    @Test fun ruleRoundtripPreservesUnknownFieldsAndNormalizesOnlyUserIdOrdering() {
        val json = JSONArray("""[{"user_ids":[99,10],"@type":"userPrivacySettingRuleRestrictUsers"},{"@type":"userPrivacySettingRuleAllowPremiumUsers"},{"future":{"b":2,"a":1},"@type":"futureRule"},{"@type":"userPrivacySettingRuleAllowAll"}]""")
        val rules = decodePrivacyRules(json)
        assertEquals(PrivacyRule.DenyUsers(setOf(10L, 99L)), rules.first())
        assertEquals(rules, decodePrivacyRules(encodePrivacyRules(rules)))
        assertEquals(1, encodePrivacyRules(rules).getJSONObject(2).getJSONObject("future").getInt("a"))
        val proposed = statusExceptions(rules, setOf(11L))
        assertEquals(PrivacyRule.DenyUsers(setOf(11L)), proposed.first())
        assertEquals(rules, proposed.drop(1))
        val explicitAllows = decodePrivacyRules(JSONArray("""[{"@type":"userPrivacySettingRuleAllowUsers","user_ids":[99,11]},{"@type":"userPrivacySettingRuleRestrictUsers","user_ids":[10]},{"@type":"userPrivacySettingRuleAllowAll"}]"""))
        assertEquals(PrivacyRule.AllowUsers(setOf(11L, 99L)), explicitAllows.first())
        assertTrue(excludesUsers(explicitAllows, setOf(10L)))
        assertEquals(explicitAllows, decodePrivacyRules(encodePrivacyRules(explicitAllows)))
    }
    @Test fun canonicalObjectsCompareStructurallyAndExistingRestrictAllNeedsNoWrite() {
        assertEquals(canonicalJson(JSONObject("""{"b":2,"a":1}""")), canonicalJson(JSONObject("""{"a":1,"b":2}""")))
        val nobody = decodePrivacyRules(JSONArray("""[{"@type":"userPrivacySettingRuleRestrictAll"}]"""))
        assertEquals(nobody, statusExceptions(nobody, setOf(10L)))
    }
}
