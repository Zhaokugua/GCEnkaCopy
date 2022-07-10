package me.exzork.commands;

import emu.grasscutter.command.CommandMap;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.server.packet.send.*;
import me.exzork.GCEnkaCopy;
import me.exzork.pojo.AvatarInfoListItem;
import me.exzork.pojo.EquipListItem;
import me.exzork.pojo.Response;
import com.google.common.reflect.TypeToken;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.CommandHandler;
import emu.grasscutter.game.player.Player;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Command(label = "enka", permission = "player.give", usage = "/enka <ssm uid|ssm name>", description = "Copy stats data from enka.shinshin.moe")
public class ApplyCommand implements CommandHandler{
    @Override
    public void execute(Player sender, Player targetPlayer, List<String> args) {
        String urlString = "https://enka.shinshin.moe/u/"+args.get(0)+"/__data.json";
        try{
            URL url = new URL(urlString);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            BufferedReader reader = connection.getResponseCode() == 200 ? new BufferedReader(new InputStreamReader(connection.getInputStream())) : new BufferedReader(new InputStreamReader(connection.getErrorStream()));
            String line;
            StringBuilder sb = new StringBuilder();
            while((line = reader.readLine()) != null){
                sb.append(line);
            }
            reader.close();
            String json = sb.toString();
            Response response = Grasscutter.getGsonFactory().fromJson(json, new TypeToken<Response>(){}.getType());
            HashMap<Integer,ArrayList<GameItem>> itemsData = new HashMap<>();
            HashMap<Integer,HashMap<String,Integer>> skillsData = new HashMap<>();
            HashMap<Integer,ArrayList<Integer>> constellationsData = new HashMap<>();
            HashMap<Integer,Integer> costumesData = new HashMap<>();
            HashMap<Integer,Avatar> avatars = new HashMap<>();

            CommandHandler.sendMessage(sender, "正在获取角色、武器和圣遗物...");
            for (AvatarInfoListItem avatarInfoListItem : response.getAvatarInfoList()){
                Avatar avatar = sender.getAvatars().getAvatarById(avatarInfoListItem.getAvatarId());
                if (avatar == null){
                    String commandAvatar = "give "+avatarInfoListItem.getAvatarId()+" lv"+response.getPlayerInfo().getShowAvatarInfoList().stream().filter(a -> a.getAvatarId() == avatarInfoListItem.getAvatarId()).findFirst().get().getLevel();
                    GCEnkaCopy.getInstance().getLogger().info(commandAvatar);
                    CommandMap.getInstance().invoke(sender,sender,commandAvatar);
                    do {
                        avatar = sender.getAvatars().getAvatarById(avatarInfoListItem.getAvatarId());
                    } while (avatar == null);
                }
                ArrayList<GameItem> items = new ArrayList<>();
                for (EquipListItem equipListItem: avatarInfoListItem.getEquipList()){
                    ItemData itemData = GameData.getItemDataMap().get(equipListItem.getItemId());
                    GameItem gameItem = new GameItem(itemData);
                    if (gameItem.getItemType() == ItemType.ITEM_WEAPON){
                        gameItem.setLevel(equipListItem.getWeapon().getLevel());
                        gameItem.setPromoteLevel(equipListItem.getWeapon().getPromoteLevel());
                        gameItem.setRefinement(equipListItem.getWeapon().getAffixMap().values().stream().toList().get(0));
                    }else if (gameItem.getItemType() == ItemType.ITEM_RELIQUARY){
                        gameItem.setLevel(equipListItem.getReliquary().getLevel());
                        gameItem.setMainPropId(equipListItem.getReliquary().getMainPropId());
                        gameItem.getAppendPropIdList().clear();
                        gameItem.getAppendPropIdList().addAll(equipListItem.getReliquary().getAppendPropIdList());
                    }
                    sender.getInventory().addItem(gameItem, ActionReason.SubfieldDrop);
                    items.add(gameItem);
                }
                itemsData.put(avatarInfoListItem.getAvatarId(), items);

                HashMap<String, Integer> skills = new HashMap<>(avatarInfoListItem.getSkillLevelMap());
                skillsData.put(avatarInfoListItem.getAvatarId(), skills);

                ArrayList<Integer> constellations = new ArrayList<>();
                if (avatarInfoListItem.getTalentIdList() != null){
                    constellations.addAll(avatarInfoListItem.getTalentIdList());
                }

                if (avatarInfoListItem.getCostumeId() != null){
                    costumesData.put(avatarInfoListItem.getAvatarId(), avatarInfoListItem.getCostumeId());
                }

                constellationsData.put(avatarInfoListItem.getAvatarId(),constellations);
                avatars.put(avatarInfoListItem.getAvatarId(), avatar);
            }

            CommandHandler.sendMessage(sender, "正在设置命座...");
            for (int avatarId: constellationsData.keySet()){
                Avatar avatar = avatars.get(avatarId);

                avatar.getTalentIdList().clear();
                avatar.setCoreProudSkillLevel(0);
                avatar.recalcStats();
                avatar.recalcConstellations();

                avatar.getTalentIdList().addAll(constellationsData.get(avatarId));
                avatar.recalcConstellations();
                for (int talentId: avatar.getTalentIdList()){
                    PacketAvatarUnlockTalentNotify packet = new PacketAvatarUnlockTalentNotify(avatar, talentId);
                    sender.sendPacket(packet);
                }
            }

            CommandHandler.sendMessage(sender, "正在设置天赋...");
            for (int avatarId: skillsData.keySet()) {
                Avatar avatar = avatars.get(avatarId);
                for (String skillId : skillsData.get(avatarId).keySet()) {
                    int skillid = Integer.parseInt(skillId);
                    Integer level = skillsData.get(avatarId).get(skillId);
                    Integer currentLevel = avatar.getSkillLevelMap().get(skillid);
                    PacketAvatarSkillChangeNotify skillPacket = new PacketAvatarSkillChangeNotify(avatar, skillid, currentLevel, level);
                    sender.sendPacket(skillPacket);
                    avatar.getSkillLevelMap().put(skillid, level);
                }
            }

            CommandHandler.sendMessage(sender, "正在装备武器和圣遗物...");
            for (int avatarId: itemsData.keySet()){
                Avatar avatar = avatars.get(avatarId);
                for (GameItem gameItem: itemsData.get(avatarId)){
                    avatar.equipItem(gameItem,true);
                }
            }

            for (int avatarId: costumesData.keySet()){
                Avatar avatar = avatars.get(avatarId);
                sender.getCostumeList().add(costumesData.get(avatarId));
                sender.getAvatars().changeCostume(avatar.getGuid(), costumesData.get(avatarId));
            }

            for (Avatar avatar: avatars.values()){
                avatar.recalcStats();
                avatar.save();
            }

            CommandHandler.sendMessage(sender, "已完成uid: "+args.get(0)+" 昵称: "+response.getPlayerInfo().getNickname()+"的拷贝！");

        }catch (Exception e){
            CommandHandler.sendMessage(sender, "加载数据时出错！确保你的uid或者昵称输入正确而且资料处于公开状态。");
            e.printStackTrace();
        }
    }
}
