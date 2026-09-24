import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * 终端贪吃蛇（纯 Java，零依赖）
 *
 * 运行环境：Termux / Linux / macOS / Windows(WSL) 任意终端
 * 编译运行：
 *   javac SnakeGame.java
 *   java SnakeGame
 *
 * 操作：
 *   W / ↑ 上    S / ↓ 下    A / ← 左    D / → 右
 *   空格 = 暂停/继续     Q = 退出
 *
 * 特性：
 *   - ANSI 转义渲染，无需任何第三方库
 *   - raw 模式读取按键（stty raw），方向键即时响应，无需回车
 *   - 每吃一个食物加速一次，难度递增
 *   - 撞墙 / 撞到自己游戏结束；暂停防误操作
 *
 * 作者：HY_VQ 项目 · 2026-08-17
 */
public class SnakeGame {

    // ── 地图尺寸（手机终端通常 ≥ 50 列 × 24 行，留安全余量） ──
    private static final int W = 46;          // 宽度（列）
    private static final int H = 22;          // 高度（行）
    private static final int INIT_DELAY = 180; // 初始帧延迟 ms（越快越难）
    private static final int MIN_DELAY = 60;   // 最快帧延迟 ms

    private final Random random = new Random();

    // 蛇：Deque 保存身体坐标（头在 first）
    private final Deque<int[]> snake = new ArrayDeque<>();
    private int dirX = 1, dirY = 0;   // 当前方向
    private int nextDirX = 1, nextDirY = 0; // 缓冲方向（防止一帧内反向）
    private int[] food = new int[2];
    private int score = 0;
    private boolean gameOver = false;
    private boolean paused = false;
    private boolean running = true;

    public static void main(String[] args) throws Exception {
        SnakeGame game = new SnakeGame();
        game.run();
    }

    private SnakeGame() {
        // 初始蛇：3 节，从左上角向右
        snake.addFirst(new int[]{H / 2, W / 4});
        snake.addLast(new int[]{H / 2, W / 4 - 1});
        snake.addLast(new int[]{H / 2, W / 4 - 2});
        spawnFood();
    }

    // ── 主循环：读输入 → 移动 → 碰撞检测 → 渲染 ──
    private void run() throws Exception {
        boolean raw = enterRawMode();
        hideCursor();
        clearScreen();
        int delay = INIT_DELAY;

        try {
            while (running) {
                long frameStart = System.currentTimeMillis();
                readInput();
                if (!paused && !gameOver) {
                    if (!move()) {
                        gameOver = true;
                    }
                }
                render();

                // 随分数加速
                delay = Math.max(MIN_DELAY, INIT_DELAY - score * 6);
                long used = System.currentTimeMillis() - frameStart;
                long sleep = delay - used;
                if (sleep > 0) Thread.sleep(sleep);

                if (gameOver) {
                    // 显示结束画面并等待按键：R 重开，Q 退出
                    while (true) {
                        int k = readKey();
                        if (k == 'r' || k == 'R') { resetGame(); break; }
                        if (k == 'q' || k == 'Q') { running = false; break; }
                    }
                }
            }
        } finally {
            restoreTerminal(raw);
            showCursor();
            clearScreen();
        }
        System.out.println("游戏结束，得分：" + score);
    }

    // ── 移动与碰撞 ──
    private boolean move() {
        // 应用缓冲方向（禁止直接反向）
        if (!(nextDirX == -dirX && nextDirY == -dirY)) {
            dirX = nextDirX;
            dirY = nextDirY;
        }
        int[] head = snake.peekFirst();
        int nx = head[0] + dirY;
        int ny = head[1] + dirX;

        // 撞墙
        if (nx < 0 || nx >= H || ny < 0 || ny >= W) return false;

        // 撞自己（新头会顶掉尾巴的位置时不算撞：先把尾移走）
        boolean eating = (nx == food[0] && ny == food[1]);
        if (!eating) snake.pollLast();
        for (int[] s : snake) {
            if (s[0] == nx && s[1] == ny) return false;
        }

        snake.addFirst(new int[]{nx, ny});

        if (eating) {
            score++;
            spawnFood();
        }
        return true;
    }

    private void spawnFood() {
        while (true) {
            int x = random.nextInt(H);
            int y = random.nextInt(W);
            boolean onSnake = false;
            for (int[] s : snake) {
                if (s[0] == x && s[1] == y) { onSnake = true; break; }
            }
            if (!onSnake) {
                food[0] = x;
                food[1] = y;
                return;
            }
        }
    }

    private void resetGame() {
        snake.clear();
        snake.addFirst(new int[]{H / 2, W / 4});
        snake.addLast(new int[]{H / 2, W / 4 - 1});
        snake.addLast(new int[]{H / 2, W / 4 - 2});
        dirX = 1; dirY = 0;
        nextDirX = 1; nextDirY = 0;
        score = 0;
        gameOver = false;
        paused = false;
        spawnFood();
        clearScreen();
    }

    // ── 输入：raw 模式无缓冲读键 ──
    private static final int KEY_UP = 1001;    // 方向键 ↑
    private static final int KEY_DOWN = 1002;  // 方向键 ↓
    private static final int KEY_RIGHT = 1003; // 方向键 →
    private static final int KEY_LEFT = 1004;  // 方向键 ←

    private void readInput() throws IOException {
        while (System.in.available() > 0) {
            int k = readKey();
            switch (k) {
                case 'w': case 'W': case KEY_UP: nextDirX = 0; nextDirY = -1; break;
                case 's': case 'S': case KEY_DOWN: nextDirX = 0; nextDirY = 1;  break;
                case 'a': case 'A': case KEY_LEFT: nextDirX = -1; nextDirY = 0; break;
                case 'd': case 'D': case KEY_RIGHT: nextDirX = 1; nextDirY = 0; break;
                case ' ': paused = !paused; break;
                case 'q': case 'Q': running = false; break;
            }
        }
    }

    /** 读取一个按键；方向键转义序列 \033[A.. 编码为 1001..1004（避免与 WASD 大写字母冲突） */
    private int readKey() throws IOException {
        int c = System.in.read();
        if (c == 27) { // ESC 转义序列
            int c2 = System.in.read();
            if (c2 == '[') {
                int c3 = System.in.read();
                if (c3 == 'A') return KEY_UP;    // ↑
                if (c3 == 'B') return KEY_DOWN;  // ↓
                if (c3 == 'C') return KEY_RIGHT; // →
                if (c3 == 'D') return KEY_LEFT;  // ←
            }
        }
        return c;
    }

    // ── 渲染：整帧构建后一次输出，避免闪烁 ──
    private void render() {
        char[][] map = new char[H][W];
        for (int i = 0; i < H; i++) {
            for (int j = 0; j < W; j++) map[i][j] = ' ';
        }
        // 边框
        for (int j = 0; j < W; j++) { map[0][j] = '#'; map[H - 1][j] = '#'; }
        for (int i = 0; i < H; i++) { map[i][0] = '#'; map[i][W - 1] = '#'; }
        // 食物
        map[food[0]][food[1]] = '*';
        // 蛇（头特殊标记）
        boolean first = true;
        for (int[] s : snake) {
            map[s[0]][s[1]] = first ? '@' : 'o';
            first = false;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\033[H"); // 光标回原点
        sb.append("  终端贪吃蛇  |  得分: ").append(score)
          .append("  |  速度: ").append(Math.max(1, INIT_DELAY - score * 6))
          .append("ms  |  W/A/S/D 方向  空格暂停  Q退出\n\n");
        for (int i = 0; i < H; i++) {
            sb.append("  ");
            for (int j = 0; j < W; j++) sb.append(map[i][j]);
            sb.append('\n');
        }
        if (paused) sb.append("\n  ⏸ 已暂停，按空格继续\n");
        if (gameOver) sb.append("\n  💥 游戏结束！得分: ").append(score)
                .append("  按 R 重开 / Q 退出\n");
        System.out.print(sb);
        System.out.flush();
    }

    // ── 终端控制 ──
    private boolean enterRawMode() throws Exception {
        try {
            // stty raw -echo：关闭行缓冲与回显；结束后恢复 cooked
            Process p = new ProcessBuilder("stty", "raw", "-echo").start();
            p.waitFor();
            return true;
        } catch (Throwable t) {
            System.out.println("⚠ 无法进入 raw 模式，按键需回车确认（体验稍差）。");
            return false;
        }
    }

    private void restoreTerminal(boolean raw) {
        if (!raw) return;
        try {
            Process p = new ProcessBuilder("stty", "cooked", "echo").start();
            p.waitFor();
        } catch (Throwable ignored) {
        }
    }

    private void clearScreen() {
        System.out.print("\033[2J\033[H");
        System.out.flush();
    }

    private void hideCursor() {
        System.out.print("\033[?25l");
        System.out.flush();
    }

    private void showCursor() {
        System.out.print("\033[?25h");
        System.out.flush();
    }
}