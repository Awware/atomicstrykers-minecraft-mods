package atomicstryker.minions.common.entity;

import java.util.ArrayList;
import java.util.List;

import atomicstryker.astarpathing.AS_PathEntity;
import atomicstryker.astarpathing.AStarNode;
import atomicstryker.astarpathing.AStarPathPlanner;
import atomicstryker.astarpathing.AStarStatic;
import atomicstryker.astarpathing.IAStarPathedEntity;
import atomicstryker.minions.common.MinionsCore;
import atomicstryker.minions.common.jobmanager.BlockTask;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;

/**
 * Minion Entity class, this is where the evil magic happens
 * 
 * @author AtomicStryker
 */

public class EntityMinion extends EntityCreature implements IAStarPathedEntity, Comparable<EntityMinion>
{
    private final int pathingCooldownTicks = 10;
    public final InventoryMinion inventory;
    public final AStarPathPlanner pathPlanner;

    public EntityPlayer master;
    public boolean inventoryFull;
    public TileEntity returnChestOrInventory;
    private AS_PathEntity pathToWalkInputCache;
    public BlockPos currentTarget;
    private int currentPathNotFoundCooldownTick;
    private int pathFindingFails;
    private int currentPathingStopCooldownTick;
    private BlockTask currentTask;
    public EntityLivingBase targetEntityToGrab;
    public float workSpeed;
    private long workBoostTime;
    public boolean isStripMining;
    private long timeLastSound;
    public boolean canPickUpItems;
    private long canPickUpItemsAgainAt;
    private long despawnTime;
    private float moveSpeed;

    public boolean followingMaster;
    public boolean returningGoods;

    private Chunk lastChunk;
    private Ticket chunkLoadingTicket;

    public static final DataParameter<Byte> IS_WORKING = EntityDataManager.createKey(EntityMinion.class, DataSerializers.BYTE);
    public static final DataParameter<Integer> X_BLOCKTASK = EntityDataManager.createKey(EntityMinion.class, DataSerializers.VARINT);
    public static final DataParameter<Integer> Y_BLOCKTASK = EntityDataManager.createKey(EntityMinion.class, DataSerializers.VARINT);
    public static final DataParameter<Integer> Z_BLOCKTASK = EntityDataManager.createKey(EntityMinion.class, DataSerializers.VARINT);
    protected static final DataParameter<String> MASTER_NAME = EntityDataManager.createKey(EntityMinion.class, DataSerializers.STRING);
    protected static final DataParameter<Byte> HELD_ITEM = EntityDataManager.createKey(EntityMinion.class, DataSerializers.BYTE);

    public EntityMinion(World var1)
    {
        super(var1);
        this.isImmuneToFire = true;

        this.moveSpeed = 1.2F;
        getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.225D);
        getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(MinionsCore.instance.minionFollowRange);

        this.pathPlanner = new AStarPathPlanner(world, this);

        // this.getNavigator().setAvoidsWater(false);
        this.tasks.addTask(0, new EntityAISwimming(this));
        this.tasks.addTask(1, new MinionAIStalkAndGrab(this, this.moveSpeed));
        this.tasks.addTask(2, new MinionAIFollowMaster(this, this.moveSpeed, 10.0F, 2.0F));
        this.tasks.addTask(3, new MinionAIWander(this, this.moveSpeed));

        inventory = new InventoryMinion(this);
        inventoryFull = false;
        currentPathNotFoundCooldownTick = 0;
        pathFindingFails = 0;
        currentPathingStopCooldownTick = 0;
        workSpeed = 1.0F;
        workBoostTime = 0L;
        isStripMining = false;
        canPickUpItems = true;
        canPickUpItemsAgainAt = 0L;
        despawnTime = -1l;

        chunkLoadingTicket = ForgeChunkManager.requestTicket(MinionsCore.instance, world, Type.ENTITY);
        if (chunkLoadingTicket != null)
        {
            lastChunk = world.getChunkFromBlockCoords(new BlockPos((int) posX, 0, (int) posZ));
            chunkLoadingTicket.bindEntity(this);
            ForgeChunkManager.forceChunk(chunkLoadingTicket, lastChunk.getPos());
        }
        else
        {
            System.err.println("Minions Minion " + this + " did not get a ForgeChunkManager ticket???");
        }

        currentTarget = BlockPos.ORIGIN;
    }

    public EntityMinion(World world, EntityPlayer playerEnt)
    {
        this(world);
        master = playerEnt;
        setMasterUserName(playerEnt.getGameProfile().getName());
    }

    @Override
    protected void entityInit()
    {
        super.entityInit();
        /*
         * boolean isWorking for SwingProgress and Sounds, set by AS_BlockTask
         */
        dataManager.register(IS_WORKING, (byte) 0);
        dataManager.register(X_BLOCKTASK, 0);
        dataManager.register(Y_BLOCKTASK, 0);
        dataManager.register(Z_BLOCKTASK, 0);
        dataManager.register(MASTER_NAME, "undef");
        dataManager.register(HELD_ITEM, (byte) 0);
    }

    public void setWorking(boolean b)
    {
        if (!world.isRemote)
        {
            dataManager.set(IS_WORKING, b ? (byte) 1 : (byte) 0);
        }
    }

    public void setMasterUserName(String name)
    {
        if (!world.isRemote)
        {
            dataManager.set(MASTER_NAME, name);
        }
    }

    public String getMasterUserName()
    {
        String s = dataManager.get(MASTER_NAME);
        return s.equals("") ? "undef" : s;
    }

    public void giveTask(BlockTask input, boolean dontReturn)
    {
        if (dontReturn)
        {
            currentTask = input;
            returningGoods = followingMaster = false;
        }
        else
        {
            currentTask = input;
            returningGoods = true;
        }
    }

    public BlockTask getCurrentTask()
    {
        return currentTask;
    }

    public boolean hasTask()
    {
        return currentTask != null;
    }

    @Override
    public boolean canBeCollidedWith()
    {
        return true;
    }

    @Override
    public boolean canBePushed()
    {
        return true;
    }

    @Override
    protected boolean canDespawn()
    {
        return false;
    }

    @Override
    public void setDead()
    {
        inventory.dropAllItems();
        super.setDead();
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound var1)
    {
        super.writeEntityToNBT(var1);
        var1.setTag("MinionInventory", this.inventory.writeToNBT(new NBTTagList()));
        var1.setString("masterUsername", getMasterUserName());
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound var1)
    {
        super.readEntityFromNBT(var1);
        NBTTagList var2 = var1.getTagList("MinionInventory", inventory.getSizeInventory());
        this.inventory.readFromNBT(var2);
        setMasterUserName(var1.getString("masterUsername"));
        master = world.getPlayerEntityByName(getMasterUserName());

        MinionsCore.instance.minionLoadRegister(this);

        currentTarget = new BlockPos((int) posX, (int) posY, (int) posZ);
    }

    public void performTeleportToTarget()
    {
        if (currentTarget != null)
        {
            this.setPositionAndUpdate(currentTarget.getX() + 0.5D, currentTarget.getY(), currentTarget.getZ() + 0.5D);
            MinionsCore.instance.sendSoundToClients(this, "entity.endermen.teleport");
        }
    }

    public void performRecallTeleportToMaster()
    {
        if (master != null)
        {
            this.setPositionAndUpdate(master.posX + 1, master.posY, master.posZ + 1);
            MinionsCore.instance.sendSoundToClients(this, "entity.endermen.teleport");
        }
    }

    /**
     * Gives a Minion a list of possible target Nodes and causes the pathplanner
     * to try and path to one of them. If pathing was underway, it is
     * interrupted.
     * 
     * @param possibles
     *            list of reachable target nodes
     * @param allowDropping
     *            whether or not drops >1 block high are allowed in the path
     */
    public void orderMinionToMoveTo(AStarNode[] possibles, boolean allowDropping)
    {
        currentTarget = new BlockPos(possibles[0].x, possibles[0].y, possibles[0].z);
        pathPlanner.getPath(doubleToInt(this.posX), doubleToInt(this.posY), doubleToInt(this.posZ), possibles, allowDropping);
    }

    /**
     * Orders a Minion to pathfind towards coordinates. The Pathplanner starts
     * working with the target, if there was another path being planned, it is
     * scrapped.
     */
    public void orderMinionToMoveTo(int targetX, int targetY, int targetZ, boolean allowDropping)
    {
        currentTarget = new BlockPos(targetX, targetY, targetZ);
        pathPlanner.getPath(doubleToInt(this.posX), doubleToInt(this.posY), doubleToInt(this.posZ), targetX, targetY, targetZ, allowDropping);
        // System.out.println("Minion ordered to move to
        // ["+targetX+"|"+targetY+"|"+targetZ+"]");
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();

        Chunk curChunk = world.getChunkFromBlockCoords(new BlockPos((int) posX, 0, (int) posZ));
        if (chunkLoadingTicket != null && curChunk != null && lastChunk != null)
        {
            if (curChunk.x != lastChunk.x || curChunk.z != lastChunk.z)
            {
                ForgeChunkManager.unforceChunk(chunkLoadingTicket, lastChunk.getPos());
                lastChunk = curChunk;
                ForgeChunkManager.forceChunk(chunkLoadingTicket, lastChunk.getPos());
            }
        }

        if (getPassengers().contains(master) && this.getNavigator().noPath())
        {
            this.rotationYaw = this.rotationPitch = 0;
        }

        if ((master != null && master.isDead) || master == null)
        {
            if (despawnTime < 0)
            {
                despawnTime = System.currentTimeMillis() + MinionsCore.instance.secondsWithoutMasterDespawn * 1000l;
            }
            else if (System.currentTimeMillis() > despawnTime)
            {
                master = null;
                dropAllItemsToWorld();
                setDead();
            }
        }
        else
        {
            despawnTime = -1l;
        }

        if (dataManager.get(IS_WORKING) != 0)
        {
            int x = dataManager.get(X_BLOCKTASK);
            int y = dataManager.get(Y_BLOCKTASK);
            int z = dataManager.get(Z_BLOCKTASK);
            BlockPos bp = new BlockPos(x, y, z);
            IBlockState is =  world.getBlockState(bp);
            Block blockID = is.getBlock();

            swingProgress += (0.17F * 0.5 * workSpeed);
            SoundType soundtype = blockID.getSoundType(is, world, bp, this);
            
            if (swingProgress > 1.0F)
            {
                swingProgress = 0;
                playSound(soundtype.getBreakSound(), soundtype.getVolume(), soundtype.getPitch());
            }

            if (blockID != Blocks.AIR)
            {
                long curTime = System.currentTimeMillis();
                if (curTime - timeLastSound > (500L / workSpeed))
                {
                    playSound(soundtype.getStepSound(), soundtype.getVolume() * 0.35F, soundtype.getPitch());
                    timeLastSound = curTime;
                }
            }
        }
        else
        {
            swingProgress = 0;
        }
    }

    @Override
    public void onEntityUpdate()
    {
        super.onEntityUpdate();

        if (workBoostTime != 0L && System.currentTimeMillis() - workBoostTime > 30000L)
        {
            workBoostTime = 0L;
            this.workSpeed = 1.0F;
        }

        if (getNavigator().getPath() != null)
        {
            if (hasReachedTarget())
            {
                getNavigator().setPath(null, this.moveSpeed);
            }
            else if (getNavigator().getPath() != null && getNavigator().getPath() instanceof AS_PathEntity && ((AS_PathEntity) getNavigator().getPath()).getTimeSinceLastPathIncrement() > 500L
                    && !world.isRemote)
            {
                currentPathingStopCooldownTick++;
                if (currentPathingStopCooldownTick > pathingCooldownTicks)
                {
                    // System.out.println("server path follow failed trigger!");
                    currentPathingStopCooldownTick = 0;

                    PathPoint nextUp = ((AS_PathEntity) getNavigator().getPath()).getCurrentTargetPathPoint();
                    if (nextUp != null)
                    {
                        ((AS_PathEntity) getNavigator().getPath()).advancePathIndex();
                        this.setPositionAndUpdate(nextUp.x + 0.5, nextUp.y + 0.5, nextUp.z + 0.5);
                        this.motionX = 0;
                        this.motionZ = 0;
                        pathPlanner.getPath(doubleToInt(this.posX), doubleToInt(this.posY) - 1, doubleToInt(this.posZ), currentTarget.getX(), currentTarget.getY(), currentTarget.getZ(), false);
                    }
                    else
                    {
                        performTeleportToTarget();
                    }
                }
            }
        }
        else if (returningGoods && !followingMaster)
        {
            runInventoryDumpLogic();
        }
    }

    public void runInventoryDumpLogic()
    {
        if (returnChestOrInventory == null)
        {
            if (master != null && !hasPath())
            {
                if (this.getDistance(master) < 2F && this.inventory.containsItems())
                {
                    dropAllItemsToWorld();
                    returningGoods = false;
                    getNavigator().setPath(null, this.moveSpeed);
                }
            }
        }
        else
        {
            if (this.getDistanceToTileEntity(returnChestOrInventory) > 4D)
            {
                if (!hasPath() || pathPlanner.isBusy())
                {
                    if (currentPathNotFoundCooldownTick > 0)
                    {
                        currentPathNotFoundCooldownTick--;
                    }
                    else
                    {
                        AStarNode[] possibles = AStarStatic.getAccessNodesSorted(world, returnChestOrInventory.getPos().getX(), returnChestOrInventory.getPos().getY(),
                                returnChestOrInventory.getPos().getZ());
                        if (possibles.length != 0)
                        {
                            orderMinionToMoveTo(possibles, false);
                        }
                    }
                }
            }
            else
            {
                if (this.inventory.containsItems() && checkReturnChestValidity())
                {
                    this.inventory.putAllItemsToInventory((IInventory) returnChestOrInventory);
                }
                returningGoods = false;
                getNavigator().setPath(null, this.moveSpeed);
            }
        }
    }

    private boolean checkReturnChestValidity()
    {
        TileEntity test = world.getTileEntity(returnChestOrInventory.getPos());
        if (test != null)
        {
            returnChestOrInventory = test;
            return true;
        }

        returnChestOrInventory = null;
        return false;
    }

    @Override
    public void onLivingUpdate()
    {
        super.onLivingUpdate();

        if (canPickUpItems)
        {
            List<Entity> collidingEntities = this.world.getEntitiesWithinAABBExcludingEntity(this, this.getEntityBoundingBox().grow(1.0D, 0.0D, 1.0D));

            if (collidingEntities != null && collidingEntities.size() > 0)
            {
                for (int i = collidingEntities.size() - 1; i >= 0; i--)
                {
                    Entity ent = collidingEntities.get(i);
                    if (!ent.isDead)
                    {
                        onCollisionWithEntity(ent);
                    }
                }
            }
        }
        else if (System.currentTimeMillis() > canPickUpItemsAgainAt)
        {
            canPickUpItems = true;
        }
    }

    private void onCollisionWithEntity(Entity collider)
    {
        if (collider instanceof EntityItem && !world.isRemote)
        {
            EntityItem itemEnt = (EntityItem) collider;

            if (itemEnt.getItem() != null)
            {
                if (itemEnt.ticksExisted > 200)
                {
                    if (this.inventory.addItemStackToInventory(itemEnt.getItem()))
                    {
                        collider.setDead();
                    }
                    else
                    {
                        this.inventoryFull = true;
                        this.world.spawnEntity(new EntityItem(world, this.posX, this.posY, this.posZ, itemEnt.getItem()));
                    }
                }
            }
        }
    }

    public void dropAllItemsToWorld()
    {
        blockItemPickUp();
        MinionsCore.instance.sendSoundToClients(this, "minions:foryou");
        if (master != null)
        {
            this.faceEntity(master, 180F, 180F);
        }
        blockItemPickUp();
        this.inventory.dropAllItems();
    }

    private void blockItemPickUp()
    {
        canPickUpItems = false;
        canPickUpItemsAgainAt = System.currentTimeMillis() + 3000L;
    }

    private double getDistanceToTileEntity(TileEntity tileent)
    {
        return AStarStatic.getDistanceBetweenCoords(doubleToInt(this.posX), doubleToInt(this.posY), doubleToInt(this.posZ), tileent.getPos().getX(), tileent.getPos().getY(), tileent.getPos().getZ());
    }

    public boolean hasReachedTarget()
    {
        return (!hasPath() && currentTarget != null && AStarStatic.getDistanceBetweenCoords(doubleToInt(this.posX), doubleToInt(this.posY), doubleToInt(this.posZ), currentTarget.getX(),
                currentTarget.getY(), currentTarget.getZ()) < 1.5D);
    }

    @Override
    public void updateAITasks()
    {
        if (pathToWalkInputCache != null)
        {
            // System.out.println("server updateEntActionState: Path being
            // input!");
            this.getNavigator().setPath(pathToWalkInputCache, this.moveSpeed);
            pathToWalkInputCache = null;
        }

        if (this.hasTask())
        {
            currentTask.onUpdate();
        }

        super.updateAITasks();
    }

    private long timelastSqueak = 0L;
    private long timeSqueakIntervals = 1000L;

    @Override
    public boolean attackEntityFrom(DamageSource var1, float var2)
    {
        if (!this.getPassengers().isEmpty())
        {
            removePassengers();
            return true;
        }

        if (var1.getTrueSource() != null && timelastSqueak + timeSqueakIntervals < System.currentTimeMillis())
        {
            timelastSqueak = System.currentTimeMillis();
            if (master != null && var1.getTrueSource().getEntityId() == master.getEntityId())
            {
                workBoostTime = System.currentTimeMillis();
                workSpeed = 2.0F;

                master.onCriticalHit(this);
                MinionsCore.instance.sendSoundToClients(this, "minions:minionsqueak");
                // worldObj.playSoundAtEntity(this, "minions:minionsqueak",
                // 1.0F, 1.0F);
                return true;
            }
        }

        return false;
    }

    public void faceBlock(int ix, int iy, int iz)
    {
        double diffX = ix - this.posX;
        double diffZ = iz - this.posZ;
        double diffY = iy - this.posY;

        double var14 = (double) MathHelper.sqrt(diffX * diffX + diffZ * diffZ);
        float var12 = (float) (Math.atan2(diffZ, diffX) * 180.0D / 3.1415927410125732D) - 90.0F;
        float var13 = (float) (-(Math.atan2(diffY, var14) * 180.0D / 3.1415927410125732D));
        this.rotationPitch = -var13;
        this.rotationYaw = var12;
    }

    @Override
    public void onFoundPath(ArrayList<AStarNode> result)
    {
        currentPathNotFoundCooldownTick = pathingCooldownTicks;
        pathFindingFails = 0;

        pathToWalkInputCache = AStarStatic.translateAStarPathtoPathEntity(result);
        // System.out.println("Path found and translated!");

        setWorking(false);
    }

    @Override
    public void onNoPathAvailable()
    {
        if (hasTask())
        {
            currentTask.onWorkerPathFailed();
        }

        currentPathNotFoundCooldownTick = pathingCooldownTicks;
        pathFindingFails++;

        if (pathFindingFails == 3)
        {
            performTeleportToTarget();
            pathFindingFails = 0;
        }

        setWorking(false);
    }

    @Override
    public ITextComponent getDisplayName()
    {
        // return ""+(Math.sqrt((this.motionX * this.motionX) + (this.motionZ *
        // this.motionZ)));
        // return ""+currentState+"/"+nextState;
        return super.getDisplayName();
    }

    private enum HeldItem
    {
        Axe(new ItemStack(Items.IRON_AXE, 1)), Pickaxe(new ItemStack(Items.IRON_PICKAXE, 1)), Shovel(new ItemStack(Items.IRON_SHOVEL, 1));

        final ItemStack item;

        HeldItem(ItemStack i)
        {
            item = i;
        }
    }

    public void setHeldItemAxe()
    {
        if (!world.isRemote)
        {
            dataManager.set(HELD_ITEM, (byte) HeldItem.Axe.ordinal());
            setHeldItem(EnumHand.MAIN_HAND, HeldItem.values()[dataManager.get(HELD_ITEM)].item);
        }
    }

    public void setHeldItemPickaxe()
    {
        if (!world.isRemote)
        {
            dataManager.set(HELD_ITEM, (byte) HeldItem.Pickaxe.ordinal());
            setHeldItem(EnumHand.MAIN_HAND, HeldItem.values()[dataManager.get(HELD_ITEM)].item);
        }
    }

    public void setHeldItemShovel()
    {
        if (!world.isRemote)
        {
            dataManager.set(HELD_ITEM, (byte) HeldItem.Shovel.ordinal());
            setHeldItem(EnumHand.MAIN_HAND, HeldItem.values()[dataManager.get(HELD_ITEM)].item);
        }
    }

    public void adaptItem(Material mat)
    {
        if (mat == Material.CLAY || mat == Material.GRASS || mat == Material.GROUND || mat == Material.SAND || mat == Material.SNOW || mat == Material.SPONGE)
        {
            setHeldItemShovel();
        }
        else if (mat == Material.CACTUS || mat == Material.CLOTH || mat == Material.LEAVES || mat == Material.PLANTS || mat == Material.VINE || mat == Material.CACTUS || mat == Material.WOOD)
        {
            setHeldItemAxe();
        }
        else
        {
            setHeldItemPickaxe();
        }
        // System.out.println("Minion adapted Item: "+heldItem);
    }

    public void dropMinionItemWithRandomChoice(ItemStack stack)
    {
        if (stack != null)
        {
            EntityItem itemEnt = new EntityItem(this.world, this.posX, this.posY - 0.3D + (double) this.getEyeHeight(), this.posZ, stack);
            itemEnt.setDefaultPickupDelay();
            float varFloatA = 0.1F;
            itemEnt.motionX = (double) (-MathHelper.sin(this.rotationYaw / 180.0F * 3.1415927F) * MathHelper.cos(this.rotationPitch / 180.0F * 3.1415927F) * varFloatA);
            itemEnt.motionZ = (double) (MathHelper.cos(this.rotationYaw / 180.0F * 3.1415927F) * MathHelper.cos(this.rotationPitch / 180.0F * 3.1415927F) * varFloatA);
            itemEnt.motionY = (double) (-MathHelper.sin(this.rotationPitch / 180.0F * 3.1415927F) * varFloatA + 0.1F);
            float randomAngle = this.rand.nextFloat() * 3.1415927F * 2.0F;
            varFloatA = this.rand.nextFloat() * 0.02F;
            itemEnt.motionX += Math.cos((double) randomAngle) * (double) varFloatA;
            itemEnt.motionY += (double) ((this.rand.nextFloat() - this.rand.nextFloat()) * 0.1F);
            itemEnt.motionZ += Math.sin((double) randomAngle) * (double) varFloatA;
            this.world.spawnEntity(itemEnt);
        }
    }

    public int doubleToInt(double input)
    {
        return AStarStatic.getIntCoordFromDoubleCoord(input);
    }

    @Override
    public int compareTo(EntityMinion mother)
    {
        if (mother.getEntityId() == this.getEntityId())
        {
            return 0;
        }
        if (mother.getEntityId() > this.getEntityId())
        {
            return -1;
        }
        return 1;
    }

}
