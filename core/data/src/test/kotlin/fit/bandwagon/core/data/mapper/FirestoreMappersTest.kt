package fit.bandwagon.core.data.mapper

import com.google.common.truth.Truth.assertThat
import fit.bandwagon.core.model.challenge.ChallengeTemplates
import org.junit.Test
import java.io.File
import java.time.LocalDate

class FirestoreMappersTest {
    // Unit tests run from the module directory; the catalogue is shared with Cloud Functions.
    private val templates = ChallengeTemplates.parse(File("../../shared/challenge-templates.json").readText())

    @Test
    fun everyTemplateSpecIsSentWithTheSchemaVersionTheServerRequires() {
        templates.forEach { template ->
            val map = FirestoreMappers.specMap(template.toSpec(LocalDate.of(2026, 11, 2)))
            assertThat(map["schemaVersion"]).isEqualTo(1)
        }
    }

    @Test
    fun specSurvivesTheRoundTripThroughFirestoreMaps() {
        val spec = templates.first().toSpec(LocalDate.of(2026, 11, 2))
        assertThat(FirestoreMappers.spec(FirestoreMappers.specMap(spec))).isEqualTo(spec)
    }
}
