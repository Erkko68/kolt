import io.github.erkko68.kolt.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals

class PickRayTest {
    private fun ray(x: Float, y: Float) = pickRay(Vec3(0f, 0f, 5f), Vec3.ZERO, Vec3(0f, 1f, 0f), 90.0, x, y, 200, 100)

    private fun assertNear(expected: Vec3, actual: Vec3) = listOf(expected.x to actual.x, expected.y to actual.y, expected.z to actual.z)
        .forEach { (e, a) -> assertEquals(e, a, 1e-5f, "$expected, but $actual") }

    @Test
    fun theCenterLooksAtTheTarget() = assertNear(Vec3(0f, 0f, -1f), ray(100f, 50f))

    /** 90° up and down is 45° each way: the top edge is as far up as it is ahead, and the view is twice as wide. */
    @Test
    fun theTopRightCornerIsUpAndTwiceAsFarRight() = assertNear(Vec3(2f, 1f, -1f).normalized(), ray(200f, 0f))
}
