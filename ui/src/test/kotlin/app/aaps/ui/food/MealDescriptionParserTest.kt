package app.aaps.ui.food

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealDescriptionParserTest {

    @Test fun `Russian meal separates components and preserves decimal portions`() {
        val meal = MealDescriptionParser.parse("гречка варёная 150,5 г, курица 100 г; яблоко 80 г")
        assertEquals(listOf("гречка варёная", "курица", "яблоко"), meal.components.map { it.foodQuery })
        assertEquals(listOf(150.5, 100.0, 80.0), meal.components.map { it.amount })
        assertTrue(meal.components.all { it.unit == PortionUnit.GRAMS && it.issue == null })
    }

    @Test fun `amount can precede identity and kilograms and liters convert separately`() {
        val meal = MealDescriptionParser.parse("0,2 кг яблока\n250 ml milk\nвода 0.3 l")
        assertEquals(listOf(200.0, 250.0, 300.0), meal.components.map { it.amount })
        assertEquals(listOf(PortionUnit.GRAMS, PortionUnit.MILLILITERS, PortionUnit.MILLILITERS), meal.components.map { it.unit })
    }

    @Test fun `split conjunction only when each component has its own portion`() {
        assertEquals(2, MealDescriptionParser.parse("курица 100 г и рис 150 г").components.size)
        val combined = MealDescriptionParser.parse("курица и рис 250 г").components.single()
        assertEquals("курица и рис", combined.foodQuery)
        assertEquals(250.0, combined.amount)
    }

    @Test fun `missing portions remain unknown and no component disappears`() {
        val parts = MealDescriptionParser.parse("гречка; неизвестный соус; яблоко 120 г").components
        assertEquals(3, parts.size)
        assertNull(parts[0].amount)
        assertNull(parts[1].amount)
        assertEquals(MealDescriptionIssue.MISSING_PORTION, parts[1].issue)
    }

    @Test fun `estimated portion needs explicit checking`() {
        val approximate = MealDescriptionParser.parse("примерно 150 г риса").components.single()
        assertEquals("риса", approximate.foodQuery)
        assertEquals(150.0, approximate.amount)
        assertTrue(approximate.needsPortionCheck)
        assertFalse(MealDescriptionParser.parse("рис 150 г").components.single().needsPortionCheck)
    }

    @Test fun `multiple numbers are ambiguous rather than silently added or chosen`() {
        val part = MealDescriptionParser.parse("рис 100 г 200 г").components.single()
        assertNull(part.amount)
        assertEquals(MealDescriptionIssue.AMBIGUOUS_PORTION, part.issue)
    }

    @Test fun `negative zero and scientific portions never become positive quantities`() {
        for (description in listOf("рис -100 г", "рис 0 г", "рис 1e300 кг", "рис +100 г")) {
            val part = MealDescriptionParser.parse(description).components.single()
            assertNull(part.amount, description)
            assertEquals(MealDescriptionIssue.INVALID_PORTION, part.issue, description)
        }
    }

    @Test fun `a weight alone does not identify a food`() {
        val part = MealDescriptionParser.parse("100 г").components.single()
        assertEquals(MealDescriptionIssue.MISSING_FOOD, part.issue)
        assertEquals("", part.foodQuery)
    }

    @Test fun `multiplication notation requires a measured total instead of silently choosing one portion`() {
        val part = MealDescriptionParser.parse("рис 2 x 100 г").components.single()
        assertNull(part.amount)
        assertEquals(MealDescriptionIssue.INVALID_PORTION, part.issue)
    }

    @Test fun `attached or Unicode minus is never discarded to infer a positive portion`() {
        for (description in listOf("рис-100 г", "рис−100 г", "рис −100 г", "рис − 100 г")) {
            assertNull(MealDescriptionParser.parse(description).components.single().amount, description)
        }
    }

    @Test fun `empty description has no ready components`() {
        assertTrue(MealDescriptionParser.parse(" \n; ").components.isEmpty())
    }
}
