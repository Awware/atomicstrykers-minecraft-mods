package atomicstryker.battletowers.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class WorldGenHandler implements IWorldGenerator
{

    private final static String fileName = "BattletowerPositionsFile.txt";

    private final static WorldGenHandler instance = new WorldGenHandler();
    private HashMap<String, Boolean> biomesMap;
    private HashMap<String, Boolean> providerMap;
    private final static ArrayList<TowerPosition> towerPositions = new ArrayList<TowerPosition>();
    private static World lastWorld;
    private final AS_WorldGenTower generator;
    private static int disableGenerationHook;

    public WorldGenHandler()
    {
        biomesMap = new HashMap<String, Boolean>();
        providerMap = new HashMap<String, Boolean>();
        generator = new AS_WorldGenTower();
        disableGenerationHook = 0;

        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void eventWorldLoad(WorldEvent.Load evt)
    {
        loadPosFile(new File(getWorldSaveDir(evt.getWorld()), fileName), evt.getWorld());
        lastWorld = evt.getWorld();
    }

    @SubscribeEvent
    public void eventWorldSave(WorldEvent.Save evt)
    {
        flushCurrentPosListToFile(evt.getWorld());
    }

    @Override
    public void generate(Random random, int chunkX, int chunkZ, World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider)
    {
        if (disableGenerationHook > 0)
        {
            return;
        }
        Biome target = world.getBiome(new BlockPos(chunkX, 0, chunkZ));
        if (target != Biome.getBiome(8) && getIsBiomeAllowed(target) && getIsChunkProviderAllowed(chunkProvider))
        {
            if (world != lastWorld)
            {
                if (lastWorld != null)
                {
                    flushCurrentPosListToFile(lastWorld);
                }
                loadPosFile(new File(getWorldSaveDir(world), fileName), world);
                lastWorld = world;
            }
            generateSurface(world, random, chunkX * 16, chunkZ * 16);
        }
    }

    private boolean getIsChunkProviderAllowed(IChunkProvider chunkProvider)
    {
        String name = chunkProvider.getClass().getSimpleName();
        if (providerMap.containsKey(name))
        {
            return providerMap.get(name);
        }

        Configuration config = AS_BattleTowersCore.instance.configuration;
        config.load();
        boolean result = config.get("ChunkProviderAllowed", name, true).getBoolean(true);
        config.save();
        providerMap.put(name, result);
        return result;
    }

    private boolean getIsBiomeAllowed(Biome target)
    {
        if (biomesMap.containsKey(target.getRegistryName().getResourcePath()))
        {
            return biomesMap.get(target.getRegistryName().getResourcePath());
        }

        Configuration config = AS_BattleTowersCore.instance.configuration;
        config.load();
        boolean result = config.get("BiomeSpawnAllowed", target.getRegistryName().getResourcePath(), true).getBoolean(true);
        config.save();
        biomesMap.put(target.getRegistryName().getResourcePath(), result);
        return result;
    }

    private void generateSurface(World world, Random random, int xActual, int zActual)
    {
        TowerPosition pos = canTowerSpawnAt(world, xActual, zActual);
        if (pos != null)
        {
            obtainTowerPosListAccess();
            towerPositions.add(pos);
            releaseTowerPosListAccess();
            int y = getSurfaceBlockHeight(world, xActual, zActual);
            if (y > 49)
            {
                pos.y = y;
                if (!attemptToSpawnTower(world, pos, random, xActual, y, zActual))
                {
                    System.out.printf("Tower Site [%d|%d] rejected: %s\n", pos.x, pos.z, generator.failState);
                    obtainTowerPosListAccess();
                    towerPositions.remove(pos);
                    releaseTowerPosListAccess();
                }
            }
        }
    }

    private boolean attemptToSpawnTower(World world, TowerPosition pos, Random random, int x, int y, int z)
    {
        int choice = generator.getChosenTowerOrdinal(world, random, x, y, z);
        pos.type = choice;

        if (choice >= 0)
        {
            pos.underground = world.rand.nextInt(100) + 1 < AS_BattleTowersCore.instance.chanceTowerIsUnderGround;
            generator.generate(world, random, x, y, z, choice, pos.underground);
            return true;
        }

        return false;
    }

    public static void generateTower(World world, int x, int y, int z, int type, boolean underground)
    {
        disableGenerationHook++;
        instance.generator.generate(world, world.rand, x, y, z, type, underground);
        obtainTowerPosListAccess();
        towerPositions.add(instance.new TowerPosition(x, y, z, type, underground));
        releaseTowerPosListAccess();
        disableGenerationHook--;
    }

    private int getSurfaceBlockHeight(World world, int x, int z)
    {
        int h = 50;

        do
        {
            h++;
        }
        while (world.getBlockState(new BlockPos(x, h, z)).getBlock() != Blocks.AIR);

        return h - 1;
    }

    public static TowerStageItemManager getTowerStageManagerForFloor(int floor)
    {
        // wait for load if it hasnt happened yet
        while (AS_BattleTowersCore.instance.floorItemManagers == null)
        {
            Thread.yield();
        }

        floor--; // subtract 1 to match the floors to the array

        if (floor >= AS_BattleTowersCore.instance.floorItemManagers.length)
        {
            floor = AS_BattleTowersCore.instance.floorItemManagers.length - 1;
        }
        if (floor < 0)
        {
            floor = 0;
        }

        return new TowerStageItemManager(AS_BattleTowersCore.instance.floorItemManagers[floor]);
    }

    private static boolean towerPositionsAccessLock;

    private synchronized static void obtainTowerPosListAccess()
    {
        int counter = 0;
        while (towerPositionsAccessLock)
        {
            if (counter >= 0)
                counter++;
            if (counter > 100000)
            {
                new Exception().printStackTrace(System.out);
                counter = -1;
            }
            Thread.yield();
        }
        towerPositionsAccessLock = true;
    }

    private static void releaseTowerPosListAccess()
    {
        towerPositionsAccessLock = false;
    }

    private TowerPosition canTowerSpawnAt(World world, int xActual, int zActual)
    {
        BlockPos spawn = world.getSpawnPoint();
        if (Math.sqrt((spawn.getX() - xActual) * (spawn.getX() - xActual) + (spawn.getZ() - zActual) * (spawn.getZ() - zActual)) < AS_BattleTowersCore.instance.minDistanceFromSpawn)
        {
            return null;
        }

        if (AS_BattleTowersCore.instance.minDistanceBetweenTowers > 0)
        {
            double mindist = 9999f;
            obtainTowerPosListAccess();
            for (TowerPosition temp : towerPositions)
            {
                int diffX = temp.x - xActual;
                int diffZ = temp.z - zActual;
                double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
                mindist = Math.min(mindist, dist);
                if (dist < AS_BattleTowersCore.instance.minDistanceBetweenTowers)
                {
                    // System.out.printf("refusing site coords [%d,%d], mindist
                    // %f\n", xActual, zActual, mindist);
                    releaseTowerPosListAccess();
                    return null;
                }
            }
            System.out.printf("Logged %d towers so far, accepted new site coords [%d,%d], mindist %f\n", towerPositions.size(), xActual, zActual, mindist);
            releaseTowerPosListAccess();
        }

        return new TowerPosition(xActual, 0, zActual, 0, false);
    }

    public class TowerPosition implements Comparable<TowerPosition>
    {
        int x;
        int y;
        int z;
        int type;
        boolean underground;

        public TowerPosition(int ix, int iy, int iz, int itype, boolean under)
        {
            x = ix;
            y = iy;
            z = iz;
            type = itype;
            underground = under;
        }

        @Override
        public String toString()
        {
            return x + " " + y + " " + z + " " + type + " " + underground;
        }

        public TowerPosition fromString(String s)
        {
            String[] data = s.split(" ");
            return new TowerPosition(Integer.valueOf(data[0]), Integer.valueOf(data[1]), Integer.valueOf(data[2]), Integer.valueOf(data[3]), Boolean.valueOf(data[4]));
        }

        @Override
        public boolean equals(Object o)
        {
            if (o instanceof TowerPosition)
            {
                TowerPosition t = (TowerPosition) o;
                return t.x == x && t.y == y && t.z == z;
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return x + z << 8 + y << 16;
        }

        @Override
        public int compareTo(TowerPosition o)
        {
            return o.x < x ? 1 : o.x > x ? -1 : o.z < z ? 1 : o.z > z ? -1 : 0;
        }
    }

    private static void loadPosFile(File file, World world)
    {
        if (!file.getAbsolutePath().contains(world.getWorldInfo().getWorldName()))
        {
            return;
        }

        obtainTowerPosListAccess();
        try
        {
            if (!file.exists())
            {
                if (!file.createNewFile())
                {
                    throw new RuntimeException("Ruins mod crashed trying to create pos file " + file);
                }
            }
            int lineNumber = 1;
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line = br.readLine();
            TowerPosition tp = instance.new TowerPosition(0, 0, 0, 0, false);
            while (line != null)
            {
                line = line.trim();
                if (!line.startsWith("#"))
                {
                    try
                    {
                        TowerPosition newtp = tp.fromString(line);
                        if (!towerPositions.contains(newtp))
                        {
                            towerPositions.add(tp.fromString(line));
                        }
                    }
                    catch (Exception e)
                    {
                        System.err.println("Battletowers positions file is invalid in line " + lineNumber + ", skipping...");
                    }
                }

                lineNumber++;
                line = br.readLine();
            }
            br.close();
            System.out.println("Battletower Positions reloaded. Lines " + lineNumber + ", entries " + towerPositions.size());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        releaseTowerPosListAccess();
    }

    private static void flushCurrentPosListToFile(World world)
    {
        if (towerPositions.isEmpty() || world.getWorldInfo().getWorldName().equals("MpServer"))
        {
            return;
        }

        obtainTowerPosListAccess();
        File file = new File(getWorldSaveDir(world), fileName);
        if (file.exists())
        {
            if (!file.delete())
            {
                throw new RuntimeException("Ruins mod crashed because it was denied file write access to " + file);
            }
        }

        try
        {
            PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)));
            pw.println("# Behold! The Battletower position management file. Below, you see all data accumulated by AtomicStrykers Battletowers during the last run of this World.");
            pw.println("# Data is noted as follows: Each line stands for one successfull Battletower spawn. Data syntax is:");
            pw.println("# xCoordinate yCoordinate zCoordinate towerType towerUnderground");
            pw.println("# everything but the last value is an integer value. Towertypes values are:");
            pw.println("# 0: Null, 1: Cobblestone, 2: Mossy Cobblestone, 3: Sandstone, 4: Ice, 5: Smoothstone, 6: Nether, 7: Jungle");
            pw.println("#");
            pw.println("# DO NOT EDIT THIS FILE UNLESS YOU ARE SURE OF WHAT YOU ARE DOING");
            pw.println("#");
            pw.println("# the primary function of this file is to enable regeneration or removal of spawned Battletowers.");
            pw.println("# that is possible via commands /regenerateallbattletowers and /deleteallbattletowers.");
            pw.println("# do not change values once towers have spawned! Either do that before creating a World (put this file in a world named folder)...");
            pw.println("# ... or use /deletebattletowers, exit the game, modify this file any way you want, load the world, then use /regeneratebattletowers!");

            for (TowerPosition t : towerPositions)
            {
                pw.println(t.toString());
            }

            pw.flush();
            pw.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        releaseTowerPosListAccess();
    }

    private static File getWorldSaveDir(World world)
    {
        ISaveHandler worldsaver = world.getSaveHandler();

        if (worldsaver.getChunkLoader(world.provider) instanceof AnvilChunkLoader)
        {
            AnvilChunkLoader loader = (AnvilChunkLoader) worldsaver.getChunkLoader(world.provider);

            for (Field f : loader.getClass().getDeclaredFields())
            {
                if (f.getType().equals(File.class))
                {
                    try
                    {
                        f.setAccessible(true);
                        return (File) f.get(loader);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }

        return null;
    }

    public static TowerPosition deleteNearestTower(World world, int x, int z)
    {
        double lowestDist = 9999d;
        TowerPosition chosen = null;

        disableGenerationHook++;
        obtainTowerPosListAccess();
        for (TowerPosition tp : towerPositions)
        {
            double dist = Math.sqrt((tp.x - x) * (tp.x - x) + (tp.z - z) * (tp.z - z));
            if (dist < lowestDist)
            {
                lowestDist = dist;
                chosen = tp;
            }
        }
        releaseTowerPosListAccess();
        disableGenerationHook--;

        if (chosen != null)
        {
            instance.generator.generate(world, world.rand, chosen.x, chosen.y, chosen.z, AS_WorldGenTower.TowerTypes.Null.ordinal(), chosen.underground);
            obtainTowerPosListAccess();
            towerPositions.remove(chosen);
            releaseTowerPosListAccess();
        }

        return chosen;
    }

    public static void deleteAllTowers(World world, boolean regenerate)
    {
        if (world != lastWorld)
        {
            flushCurrentPosListToFile(lastWorld);
            loadPosFile(new File(getWorldSaveDir(world), fileName), world);
            lastWorld = world;
        }

        for (Object o : world.loadedEntityList)
        {
            if (o instanceof AS_EntityGolem)
            {
                ((Entity) o).setDead();
            }
        }

        disableGenerationHook++;
        obtainTowerPosListAccess();
        for (TowerPosition tp : towerPositions)
        {
            instance.generator.generate(world, world.rand, tp.x, tp.y, tp.z, regenerate ? tp.type : AS_WorldGenTower.TowerTypes.Null.ordinal(), tp.underground);
        }

        if (!regenerate)
        {
            towerPositions.clear();
        }
        releaseTowerPosListAccess();
        disableGenerationHook--;
    }

}
