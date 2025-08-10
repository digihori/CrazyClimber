package tk.horiuchi.crazyclimber.core

enum class WindowState { OPEN, HALF, CLOSED }
enum class HandPair { BOTH_UP, BOTH_DOWN, L_UP_R_DOWN, L_DOWN_R_UP }
enum class Stability { STABLE, UNSTABLE }
enum class PlayerAnim { IDLE, CLIMBING, SHIFTING, FALLING }
enum class LeverDir { CENTER, UP, DOWN, LEFT, RIGHT }

data class Cell(val col: Int, val floor: Int)
