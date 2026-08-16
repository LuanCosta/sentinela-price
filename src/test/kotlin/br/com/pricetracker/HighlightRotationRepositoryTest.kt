package br.com.pricetracker

import br.com.pricetracker.data.repository.HighlightRotationRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightRotationRepositoryTest {
    @Test fun `rotaciona categorias e retoma do cursor salvo`() = runBlocking {
        val repository = HighlightRotationRepository(testDatabase())
        val categories = listOf("A", "B", "C", "D")

        assertEquals(listOf("A", "B", "C"), repository.nextBatch(categories, 3))
        assertEquals(listOf("D", "A", "B"), repository.nextBatch(categories, 3))
        assertEquals(listOf("C", "D", "A"), repository.nextBatch(categories, 3))
    }
}
