package com.example.examplemod;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.stream.Collectors;

import static com.example.examplemod.BartioneHelper.*;
import static com.example.examplemod.RenderHelper.clearEntities;

@EventBusSubscriber(modid = ExampleMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class VillagerTrader {
    // Состояния
    public enum State {
        STARTED,
        FINDING_CHESTS,
        MOVING_CHEST,
        FINDING,
        MOVING,
        AIMING,
        TRADING,
        SOLVER,
        STOPED
    }
    // Клавиши управления
    public static final KeyMapping TOGGLE_KEY = new KeyMapping(
            "key.examplemod.enable",
            GLFW.GLFW_KEY_EQUAL,
            "key.category.examplemod"
    );
    // Клавиши управления
    public static final KeyMapping next = new KeyMapping(
            "key.examplemod.next",
            GLFW.GLFW_KEY_MINUS,
            "key.category.examplemod"
    );
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Minecraft minecraft = Minecraft.getInstance();
    private static State currentState = null;
    private static Villager currentVillager = null;
    private static BlockPos currentChest = null;
    private static Item currentItemTrade = null;
    private static int currentMaxCostTrade = 0;
    private static VillagerProfession currentVillagerProfession = null;
    private static Queue<Villager> villagerQueue = new LinkedList<>();
    private static Queue<BlockPos> chestQueue = new LinkedList<>();
    private static final double radiusSearchVillager = 100;
    private static final int radiusSearchChests = 5;
    private static boolean isEnabled = false;
    private static boolean isTrading = false;
    private static boolean hasGoalPath = false;
    private static boolean hasGoalAim = false;
    private static int timeToTrade = 0;
    private static int countTrade = 0;

    private static boolean NEXT = false;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Player player = minecraft.player;
        if (player == null) return;
        if (TOGGLE_KEY.consumeClick()) {
            // Принудительно обновляем размеры для всех игроков
            isEnabled = !isEnabled;
            if (isEnabled) {
                currentState = State.STARTED;
            } else {
                handleStop();
            }
            LOGGER.info("Mod {}", isEnabled ? "isEnabled" : "DISABLED");
        }
        if (next.consumeClick()) {
            NEXT = !NEXT;
        }

        if (!isEnabled) return;

        switch (currentState) {
            case STARTED -> handleStart();
            case FINDING_CHESTS -> handleFindChests();
            case MOVING_CHEST -> handleMoveToNextChest();
            case FINDING -> handleFindVillagers();
            case MOVING -> handleMoveToNextVillager();
            case AIMING -> handleAimToVillager();
            case TRADING -> handleTradeToVillager();
            case SOLVER -> handleSolver();
            case STOPED-> handleStop();
        }
    }

    private static void handleStart() {
        if(NEXT) {
            currentVillagerProfession = VillagerProfession.CLERIC;
            currentItemTrade = Items.ROTTEN_FLESH;
            currentMaxCostTrade = 32;
        } else {
            currentVillagerProfession = VillagerProfession.FARMER;
            currentItemTrade = Items.PUMPKIN;
            currentMaxCostTrade = 6;
        }
        currentState = State.FINDING_CHESTS;
    }
    private static void handleFindChests() {
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            currentState = State.SOLVER;
            return;
        }
        // Где-то в вашем коде, где вызывается поиск сундуков:
        List<BlockPos> chests = findChestsNearPlayer(radiusSearchChests);
        if (chests.isEmpty()) {
            LOGGER.info("Сундуков в радиусе {} блоков не найдено", radiusSearchChests);
        } else {
            LOGGER.info("Найдены сундуки ({} шт.) на позициях: {}", chests.size(), formatBlockPosList(chests));
        }
        chestQueue = new LinkedList<>(chests);
        currentState = State.MOVING_CHEST;
    }
    private static void handleMoveToNextChest() {
        if(!hasGoalPath) {
            currentChest = chestQueue.poll();
            if (currentChest == null) {
                currentState = State.FINDING;
                stopGoal();
                hasGoalPath = false;
                return;
            } else {
                setGoal(currentChest);
                hasGoalPath = true;
                LOGGER.info("goal chest set");
            }
        }
        if(hasReachedGoal()){
            hasGoalPath = false;
            currentState = State.MOVING_CHEST;
            LOGGER.info("goal chest reached");
            return;
        }
    }
    private static void handleFindVillagers() {
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            currentState = State.SOLVER;
            return;
        }
        AABB searchBox =  getSearchBox();
        if(searchBox == null) {
            currentState = State.STOPED;
               return;
        }
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, searchBox);
        List<Villager> filtered = villagers.stream()
                .filter(v -> v.getVillagerData().getProfession() == currentVillagerProfession)
                .toList();
        villagerQueue = new LinkedList<>(filtered);
        if (villagerQueue.isEmpty()) {
            currentState = State.SOLVER;
            return;
        } else {
            currentState = State.MOVING;
            villagerQueue.forEach(RenderHelper::addEntity);
        }
    }
    private static void handleMoveToNextVillager() {
        if(!hasGoalPath) {
            currentVillager = villagerQueue.poll();
            if (currentVillager == null) {
                currentState = State.SOLVER;
                stopGoal();
                hasGoalPath = false;
                return;
            } else {
                setGoal(currentVillager);
                hasGoalPath = true;
                LOGGER.info("goal set");
            }
        }
        if(hasReachedGoal()){
            hasGoalPath = false;
            currentState = State.AIMING;
            LOGGER.info("goal reached");
            return;
        }
    }

    private static void handleAimToVillager() {
        if(!hasGoalAim) {
            if (currentVillager == null) {
                currentState = State.SOLVER;
                stopAiming();
                hasGoalAim = false;
                return;
         }
            startAiming(currentVillager);
            hasGoalAim = true;
            LOGGER.info("aim set");
        }
        if(hasReachedAim()) {
            stopAiming();
            hasGoalAim = false;
            currentState = State.SOLVER;
            isTrading = true;
        }
    }
    private static void handleTradeToVillager() {
        Player player = minecraft.player;
        if(player == null){
            currentState= State.STOPED;
        }
        if (currentVillager == null) {
            currentState = State.SOLVER;
            return;
        }
        if (checkTradeCooldown()) return;

        interactWithVillager();

        MerchantMenu merchantMenu = getMerchantMenu();
        if (merchantMenu == null) {
             return;
        }

        int cost = findCostItemToEmeraldTrade(merchantMenu);
        if (cost > currentMaxCostTrade) {
            closeMerchantContainer(merchantMenu);
            currentState = State.SOLVER;
            return;
        }
        int itemSlot = findItemInInventoryTradeTrading(merchantMenu);
        if (itemSlot == -1) {
            LOGGER.info("{} не найден в инвентаре, в количесте {}>!", currentItemTrade, currentMaxCostTrade);
            closeMerchantContainer(merchantMenu);
            currentState = State.SOLVER;
            return;
        }
        moveItemToPaymentSlot(merchantMenu, itemSlot);

        moveEmeraldsFromTrade(merchantMenu);
        closeMerchantContainer(merchantMenu);
        currentState = State.SOLVER;
    }
    private static void handleSolver() {
        if(isTrading) {
            if(countTrade < 3) {
                currentState = State.TRADING;
                countTrade++;
            } else {
                countTrade = 0;
                isTrading = false;
                currentState = State.MOVING;
            }
        } else {
            currentState = State.STOPED;
        }
    }
    private static void handleStop() {
        clear();
        isEnabled = false;
    }
    private static void clear() {
        villagerQueue.clear();
        chestQueue.clear();
        currentVillager = null;
        currentVillagerProfession = null;
        currentItemTrade = null;
        currentMaxCostTrade = 0;
        hasGoalPath = false;
        hasGoalAim = false;
        isTrading = false;
        timeToTrade = 0;
        countTrade = 0;
        clearEntities();
        stopGoal();
        stopAiming();
    }
    private static AABB getSearchBox() {
        Player player = minecraft.player;
        if(player == null) {
            return null;
        }
        BlockPos playerPos = player.blockPosition();
        return new AABB(
                playerPos.getX() - radiusSearchVillager, playerPos.getY() - radiusSearchVillager, playerPos.getZ() - radiusSearchVillager,
                playerPos.getX() + radiusSearchVillager, playerPos.getY() + radiusSearchVillager, playerPos.getZ() + radiusSearchVillager
        );
    }
    private static boolean checkTradeCooldown() {
        if (timeToTrade < 5) {
            timeToTrade++;
            return true;
        }
        timeToTrade = 0;
        return false;
    }
    private static void interactWithVillager() {
        ServerboundInteractPacket packet = ServerboundInteractPacket.createInteractionPacket(
                currentVillager, false, InteractionHand.MAIN_HAND
        );
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().send(packet);
        }
    }
    private static MerchantMenu getMerchantMenu() {
        Player player = minecraft.player;
        if (player == null || !(player.containerMenu instanceof MerchantMenu merchantMenu)) {
            return null;
        }
        return merchantMenu;
    }
    private static int findCostItemToEmeraldTrade(MerchantMenu merchantMenu) {
        MerchantOffers offers = merchantMenu.getOffers();
        for (MerchantOffer offer : offers) {
            if (offer.getResult().getItem() == Items.EMERALD &&
                    offer.getCostA().getItem() == currentItemTrade) {
                return offer.getCostA().getCount();
            }
        }
        return -1;
    }

    private static int findItemInInventoryTradeTrading(MerchantMenu merchantMenu) {
        for (int i = 3; i < 39; i++) {
            Slot slot = merchantMenu.getSlot(i);
            if (!slot.getItem().isEmpty() &&
                    slot.getItem().getItem() == currentItemTrade &&
                    slot.getItem().getCount() >= currentMaxCostTrade) {
                return i;
            }
        }
        return -1;
    }
    private static void moveItemToPaymentSlot(MerchantMenu merchantMenu, int itemSlot) {
        int containerId = merchantMenu.containerId;
        int stateId = merchantMenu.incrementStateId();

        // Выбираем глину
        ServerboundContainerClickPacket pickItem = new ServerboundContainerClickPacket(
                containerId, stateId, itemSlot, 0, ClickType.PICKUP, ItemStack.EMPTY, Int2ObjectMaps.emptyMap()
        );
        Objects.requireNonNull(minecraft.getConnection()).send(pickItem);

        // Кладём глину в слот оплаты
        ServerboundContainerClickPacket placeItem = new ServerboundContainerClickPacket(
                containerId, stateId + 1, 0, 0, ClickType.PICKUP, ItemStack.EMPTY, Int2ObjectMaps.emptyMap()
        );
        minecraft.getConnection().send(placeItem);
    }
    private static void moveEmeraldsFromTrade(MerchantMenu merchantMenu) {
        int containerId = merchantMenu.containerId;
        int stateId = merchantMenu.incrementStateId();

        ServerboundContainerClickPacket moveEmeralds = new ServerboundContainerClickPacket(
                containerId, stateId, 2, 0, ClickType.QUICK_MOVE, ItemStack.EMPTY, Int2ObjectMaps.emptyMap()
        );
        Objects.requireNonNull(minecraft.getConnection()).send(moveEmeralds);
    }
    private static void closeMerchantContainer(MerchantMenu merchantMenu) {
        ServerboundContainerClosePacket closePacket = new ServerboundContainerClosePacket(merchantMenu.containerId);
        Objects.requireNonNull(minecraft.getConnection()).send(closePacket);
        if(minecraft.player == null){
            return;
        }
        minecraft.player.closeContainer();
    }
    // Добавьте этот метод в ваш класс BartioneHelper:
    private static String formatBlockPosList(List<BlockPos> positions) {
        return positions.stream()
                .map(pos -> String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ()))
                .collect(Collectors.joining(", "));
    }
}
