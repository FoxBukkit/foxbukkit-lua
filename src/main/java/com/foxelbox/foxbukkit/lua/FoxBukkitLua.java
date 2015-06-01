/**
 * This file is part of FoxBukkitLua.
 *
 * FoxBukkitLua is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FoxBukkitLua is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FoxBukkitLua.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.foxelbox.foxbukkit.lua;

import com.foxelbox.dependencies.config.Configuration;
import com.foxelbox.dependencies.redis.RedisManager;
import com.foxelbox.dependencies.threading.SimpleThreadCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.luaj.vm2.LuaValue;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;

public class FoxBukkitLua extends JavaPlugin {
    public static FoxBukkitLua instance;

    public Configuration configuration;
    public RedisManager redisManager;
    private final HashMap<String, LuaThread> luaThreadList = new HashMap<>();

    public File getLuaFolder() {
        return new File(getDataFolder(), "base");
    }

    public File getLuaModulesFolder() {
        return new File(getDataFolder(), "modules");
    }

    public File getLuaScriptsFolder() {
        return new File(getDataFolder(), "scripts");
    }

    private void cleanupLuaThreadList() {
        synchronized (luaThreadList) {
            for (LuaThread t : luaThreadList.values()) {
                if(!t.isRunning()) {
                    luaThreadList.remove(t.getModule());
                }
            }
        }
    }

    private void terminateLuaThread(String module) {
        synchronized (luaThreadList) {
            LuaThread luaThread = luaThreadList.remove(module);
            if(luaThread != null) {
                luaThread.terminate();
            }
        }
    }

    private void terminateAllLuaThreads() {
        synchronized (luaThreadList) {
            for(LuaThread t : luaThreadList.values()) {
                t.terminate();
            }
            luaThreadList.clear();
        }
    }

    private void startLuaThread(String module, boolean overwrite) {
        synchronized (luaThreadList) {
            LuaThread luaThread = luaThreadList.get(module);
            if(luaThread != null && luaThread.isRunning()) {
                if(overwrite) {
                    luaThread.terminate();
                } else {
                    return;
                }
            }
            luaThread = new LuaThread(module);
            luaThreadList.put(module, luaThread);
            luaThread.start();
        }
    }

    private void startAllLuaThreads(boolean overwrite) {
        synchronized (luaThreadList) {
            for(String module : listLuaModules()) {
                startLuaThread(module, overwrite);
            }
        }
    }

    private Collection<String> listLuaModules() {
        LinkedList<String> luaModules = new LinkedList<>();
        for(File module : getLuaModulesFolder().listFiles()) {
            if(module.canRead() && module.isDirectory()) {
                luaModules.add(module.getName());
            } else {
                System.err.println("Invalid Lua module " + module.getName() + " (not a directory or unreadable)");
            }
        }
        return luaModules;
    }

    private void restartAllLuaThreads() {
        terminateAllLuaThreads();
        startAllLuaThreads(false);
    }

    @Override
    public void onDisable() {
        terminateAllLuaThreads();
    }

    private StringBuilder makeMessageBuilder() {
        return new StringBuilder("\u00a75[FB] \u00a7f");
    }

    @Override
    public void onEnable() {
        getLuaFolder().mkdirs();
        getLuaModulesFolder().mkdirs();
        getLuaScriptsFolder().mkdirs();

        instance = this;
        configuration = new Configuration(getDataFolder());
        redisManager = new RedisManager(new SimpleThreadCreator(), configuration);

        restartAllLuaThreads();

        getServer().getPluginCommand("lua_reload").setExecutor(new CommandExecutor() {
            @Override
            public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
                if(strings.length > 0) {
                    startLuaThread(strings[0], true);
                    commandSender.sendMessage(makeMessageBuilder().append("Reloaded Lua module ").append(strings[0]).toString());
                    return true;
                }
                restartAllLuaThreads();
                commandSender.sendMessage(makeMessageBuilder().append("Reloaded all Lua modules").toString());
                return true;
            }
        });

        getServer().getPluginCommand("lua_load").setExecutor(new CommandExecutor() {
            @Override
            public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
                if(strings.length > 0) {
                    startLuaThread(strings[0], false);
                    commandSender.sendMessage(makeMessageBuilder().append("Loaded Lua module ").append(strings[0]).toString());
                    return true;
                }
                return false;
            }
        });

        getServer().getPluginCommand("lua_unload").setExecutor(new CommandExecutor() {
            @Override
            public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
                if(strings.length > 0) {
                    terminateLuaThread(strings[0]);
                    commandSender.sendMessage(makeMessageBuilder().append("Unloaded Lua module ").append(strings[0]).toString());
                    return true;
                }
                return false;
            }
        });

        getServer().getPluginCommand("lua_run").setExecutor(new CommandExecutor() {
            @Override
            public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
                if(strings.length < 2) {
                    return false;
                }
                LuaThread luaThread;
                synchronized (luaThreadList) {
                    luaThread = luaThreadList.get(strings[0]);
                }
                if(luaThread == null) {
                    return false;
                }

                LuaValue code;
                try {
                    code = luaThread.g.load(Utils.concatArray(strings, 1, ""));
                } catch (Exception e) {
                    commandSender.sendMessage(makeMessageBuilder().append("Error in Lua code: ").append(e.getMessage()).toString());
                    return true;
                }

                LuaValue ret = new LuaThread.LuaFunctionInvoker(luaThread, code).getResult();
                commandSender.sendMessage(makeMessageBuilder().append("Code = ").append(ret).toString());
                return true;
            }
        });

        getServer().getPluginCommand("lua_runfile").setExecutor(new CommandExecutor() {
            @Override
            public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
                if(strings.length < 2) {
                    return false;
                }
                LuaThread luaThread;
                synchronized (luaThreadList) {
                    luaThread = luaThreadList.get(strings[0]);
                }
                if(luaThread == null) {
                    return false;
                }

                LuaValue code;
                try {
                    code = luaThread.g.loadfile(new File(getLuaScriptsFolder(), strings[1]).getAbsolutePath());
                } catch (Exception e) {
                    commandSender.sendMessage(makeMessageBuilder().append("Error in Lua file: ").append(e.getMessage()).toString());
                    return true;
                }

                LuaValue ret = new LuaThread.LuaFunctionInvoker(luaThread, code).getResult();
                commandSender.sendMessage(makeMessageBuilder().append("Code = ").append(ret).toString());
                return true;
            }
        });

        getServer().getPluginCommand("lua_list").setExecutor(new CommandExecutor() {
            @Override
            public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
                cleanupLuaThreadList();
                StringBuilder ret = makeMessageBuilder().append("Lua modules: ");
                boolean isFirst = true;
                for(String module : listLuaModules()) {
                    if(isFirst) {
                        isFirst = false;
                    } else {
                        ret.append(", ");
                    }
                    ret.append(luaThreadList.containsKey(module) ? "\u00a72" : "\u00a74");
                    ret.append(module);
                }
                commandSender.sendMessage(ret.toString());
                return false;
            }
        });
    }
}
