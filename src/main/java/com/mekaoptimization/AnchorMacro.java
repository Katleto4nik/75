package com.mekaoptimization;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LEGIT MODE.
 *
 * When you place a respawn anchor:
 *   1) random 11-19 ms  -> select the glowstone slot
 *   2) random 29-38 ms  -> right-click the anchor ONCE
 *   3) random 12-18 ms  -> select the slot with the anchor OR a sword (50 / 50 chance)
 *   4) random 13-17 ms  -> right-click the anchor ONCE
 * Nothing is switched back afterwards.
 *
 * Legit rules:
 *   - never rotates the camera and never clicks anything you are not really aiming at
 *     (the real crosshair target must be the anchor -> no clicking through walls, reach is vanilla)
 *   - does nothing with a GUI open, with the window unfocused, in spectator or while sneaking
 *   - if you touch the hotbar yourself while it runs, it stops and leaves your slot alone
 *   - an anchor you place yourself exactly 1 block directly in front of you is ignored
 *   - human-like (bell-shaped, never repeating) delays
 */
public final class AnchorMacro {
    // ---- timings (ms, inclusive) ----
    private static final int GLOW_SELECT_MIN_MS = 11;
    private static final int GLOW_SELECT_MAX_MS = 19;

    private static final int CHARGE_CLICK_MIN_MS = 29;
    private static final int CHARGE_CLICK_MAX_MS = 38;

    private static final int FINISH_SELECT_MIN_MS = 12;
    private static final int FINISH_SELECT_MAX_MS = 18;

    private static final int FINISH_CLICK_MIN_MS = 13;
    private static final int FINISH_CLICK_MAX_MS = 17;

    // only used when we have to wait (anchor not placed yet / crosshair not on the anchor yet)
    private static final int POLL_MIN_MS = 6;
    private static final int POLL_MAX_MS = 14;

    private static final int MAX_WAIT_FOR_ANCHOR_STEPS = 30;
    private static final int MAX_WAIT_FOR_AIM_STEPS = 12;
    private static final long COOLDOWN_MS = 300;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile long lastFinishMs = 0;

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MekaOptimization-Timer");
                t.setDaemon(true);
                return t;
            });

    private AnchorMacro() {
    }

    public static void init() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient() || hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (RUNNING.get()) return ActionResult.PASS;
            if (System.currentTimeMillis() - lastFinishMs < COOLDOWN_MS) return ActionResult.PASS;
            if (!player.getStackInHand(hand).isOf(Items.RESPAWN_ANCHOR)) return ActionResult.PASS;

            MinecraftClient mc = MinecraftClient.getInstance();
            ClientPlayerEntity me = mc.player;
            ClientWorld cw = mc.world;
            if (me == null || cw == null || mc.interactionManager == null) return ActionResult.PASS;

            // legit gates
            if (mc.currentScreen != null || !mc.isWindowFocused()) return ActionResult.PASS;
            if (me.isSpectator() || !me.isAlive()) return ActionResult.PASS;
            // with Shift a right-click places blocks instead of using them
            if (me.isSneaking()) return ActionResult.PASS;

            BlockPos clicked = hit.getBlockPos();
            var clickedState = world.getBlockState(clicked);

            // clicking an existing anchor is not a placement
            if (clickedState.isOf(Blocks.RESPAWN_ANCHOR)) return ActionResult.PASS;

            BlockPos placePos = clickedState.isReplaceable() ? clicked : clicked.offset(hit.getSide());

            // anchor placed by you 1 block directly in front of you -> do nothing
            if (isDirectlyInFront(me, placePos)) return ActionResult.PASS;

            if (!RUNNING.compareAndSet(false, true)) return ActionResult.PASS;
            new Sequence(mc, cw, placePos, me.getInventory().getSelectedSlot()).start();
            return ActionResult.PASS;
        });
    }

    /** The block right in front of the player (feet level or head level). */
    private static boolean isDirectlyInFront(ClientPlayerEntity player, BlockPos pos) {
        BlockPos front = player.getBlockPos().offset(player.getHorizontalFacing());
        return pos.equals(front) || pos.equals(front.up());
    }

    private static final class Sequence {
        private final MinecraftClient mc;
        private final ClientWorld world;
        private final BlockPos anchorPos;
        private final int anchorSlot;
        private int expectedSlot;
        private int waitAnchorSteps = 0;
        private int waitAimSteps = 0;

        Sequence(MinecraftClient mc, ClientWorld world, BlockPos anchorPos, int anchorSlot) {
            this.mc = mc;
            this.world = world;
            this.anchorPos = anchorPos;
            this.anchorSlot = anchorSlot;
            this.expectedSlot = anchorSlot;
        }

        void start() {
            next(this::selectGlowstone, Humanizer.delay(GLOW_SELECT_MIN_MS, GLOW_SELECT_MAX_MS));
        }

        /** Runs a step on the game thread after the given delay (ms). */
        private void next(Runnable step, long delayMs) {
            TIMER.schedule(() -> mc.execute(() -> {
                try {
                    if (!stillValid()) {
                        finish();
                        return;
                    }
                    // you changed the hotbar slot yourself -> stop, don't fight the player
                    if (mc.player.getInventory().getSelectedSlot() != expectedSlot) {
                        finish();
                        return;
                    }
                    step.run();
                } catch (Throwable t) {
                    MekaOptimizationClient.LOGGER.error("Anchor macro failed", t);
                    finish();
                }
            }), delayMs, TimeUnit.MILLISECONDS);
        }

        private boolean stillValid() {
            return mc.player != null && mc.world == world && mc.interactionManager != null
                    && mc.currentScreen == null && mc.isWindowFocused()
                    && !mc.player.isSpectator() && mc.player.isAlive();
        }

        private boolean anchorPresent() {
            return world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR);
        }

        private void finish() {
            lastFinishMs = System.currentTimeMillis();
            RUNNING.set(false);
        }

        private long pollDelay() {
            return Humanizer.delay(POLL_MIN_MS, POLL_MAX_MS);
        }

        // ---- steps ----

        /** 1) select the glowstone slot (11-19 ms after placing). */
        private void selectGlowstone() {
            // the anchor must really be placed (client prediction / server confirmation)
            if (!anchorPresent()) {
                if (++waitAnchorSteps > MAX_WAIT_FOR_ANCHOR_STEPS) {
                    finish();
                } else {
                    next(this::selectGlowstone, pollDelay());
                }
                return;
            }

            PlayerInventory inv = mc.player.getInventory();
            int glowSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.GLOWSTONE)) {
                    glowSlot = i;
                    break;
                }
            }
            if (glowSlot == -1) {
                finish();
                return;
            }

            inv.setSelectedSlot(glowSlot);
            expectedSlot = glowSlot;
            next(this::chargeAnchor, Humanizer.delay(CHARGE_CLICK_MIN_MS, CHARGE_CLICK_MAX_MS));
        }

        /** 2) ONE right-click with glowstone (29-38 ms later). */
        private void chargeAnchor() {
            if (!clickAnchor(this::chargeAnchor)) return;
            next(this::selectFinisher, Humanizer.delay(FINISH_SELECT_MIN_MS, FINISH_SELECT_MAX_MS));
        }

        /** 3) select the slot with the anchor OR a sword, 50 / 50 (12-18 ms later). Nothing is switched back. */
        private void selectFinisher() {
            PlayerInventory inv = mc.player.getInventory();

            int swordSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isIn(ItemTags.SWORDS)) {
                    swordSlot = i;
                    break;
                }
            }

            boolean pickSword = ThreadLocalRandom.current().nextBoolean();
            int target = (pickSword && swordSlot != -1) ? swordSlot : anchorSlot; // no sword in hotbar -> anchor slot

            inv.setSelectedSlot(target);
            expectedSlot = target;
            next(this::explodeAnchor, Humanizer.delay(FINISH_CLICK_MIN_MS, FINISH_CLICK_MAX_MS));
        }

        /** 4) ONE right-click again (13-17 ms later). */
        private void explodeAnchor() {
            if (!clickAnchor(this::explodeAnchor)) return;
            finish();
        }

        /**
         * Right-click the anchor only if the real crosshair is on it.
         * Returns true if the click was made; false if it was rescheduled or the macro stopped.
         */
        private boolean clickAnchor(Runnable retryStep) {
            if (!anchorPresent()) {
                finish();
                return false;
            }
            BlockHitResult aimed = aimedAnchor();
            if (aimed == null) {
                if (++waitAimSteps > MAX_WAIT_FOR_AIM_STEPS) {
                    finish();
                } else {
                    next(retryStep, pollDelay());
                }
                return false;
            }
            waitAimSteps = 0;

            ClientPlayerEntity player = mc.player;
            ActionResult result = mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, aimed);
            if (result.isAccepted()) {
                player.swingHand(Hand.MAIN_HAND);
            }
            return true;
        }

        /** The real crosshair hit, only if it is exactly the anchor. */
        private BlockHitResult aimedAnchor() {
            HitResult target = mc.crosshairTarget;
            if (target instanceof BlockHitResult bhr
                    && bhr.getType() == HitResult.Type.BLOCK
                    && bhr.getBlockPos().equals(anchorPos)) {
                return bhr;
            }
            return null;
        }
    }
}
