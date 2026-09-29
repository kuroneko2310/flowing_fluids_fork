1.0.7
- 増水の脅威度を大幅に引き上げ、氾濫まで起こるようにした。ディメンションごとに河川水位（保存される）を持ち、雨で最大水位の45%（警戒レベル3 避難判断）、雷雨で100%（既定 海面+6ブロック）へ数分で上がる。上昇中はプレイヤー周辺の川へ上流からの流入として水を足し、川が堤防を越えて氾濫原へあふれる。乾いた土は雨を吸うので干ばつ中は上がりにくく、春の雪解けでも氾濫注意まで上がる（`/ff weather high_water status|stage|max_stage|rain_stage|rise_per_day|inflow_samples|inflow_amount`）
- 増水警報を警戒レベル1〜5（水防団待機・氾濫注意・避難判断・氾濫危険・氾濫発生）の色付き表示にした。レベル3以上はチャットで告知、レベル4以上は警報音、水位が戻ると解除を告知。レベル5では近くの川を中心に洪水イベントが自動で始まる（`triggers_flood_events`）。水位が高いほど増水した川の流れが速くなり、雷雨中の川は押し流す力が強くなる
- 雨が止んだ後の減水を河川水位に連動させた。水位線より下の水は水位と一緒に引く
- 蒸発: 満水（レベル8）の水面が蒸発・干ばつによる減水・熱源による蒸発の対象外になっていた問題を修正。干ばつで満水の池や湖が減らず、溶岩や火の隣の満水も蒸発しなかった
- 蒸発: 干ばつ減水が地表蒸発と同じ乱数を使い回していたため、ほぼ発生しなかった問題を修正（独立した判定にした）
- 蒸発: 熱波・乾季の蒸発倍率が確率上限1で切り捨てられ効果がなかった問題を修正。倍率が2倍になるごとに、より深い水たまり（+1レベル）まで乾くようにした。干ばつ減水も熱波・乾季・季節で速くなる。乾燥イベントを全部切っても季節の蒸発倍率が効くようにした
- 蒸発: 雨上がりの減水が夜間だと止まり、減水期間の終わりに増水分が一度に消えていた問題を修正
- 蒸発: 海面超過水の除去が拒否された時（湧き水保護など）にその水の流れ処理まで止まり、水が固まっていた問題を修正
- 蒸発: 空が見えるかの判定を高さマップで先に済ませ、建築上限まで毎回ブロックを読んでいた負荷を削減。熱源の周囲75ブロック走査も確率判定の後に回した
- コマンドを整理。`/ff` を `/flowing_fluids` の短縮形として追加し、`/ff status`（現在の状況まとめ）と `/ff weather`（雨・豪雨・増水・洪水・乾燥・雪解け・季節・地下水の入口）を追加。よく使う設定を1〜2語で呼べるようにした（`flow`, `speed`, `evaporation`, `springs`, `pressure`, `waterlogging`, `playstyle`, `performance`, `debug`, `create`）。中身が部分的に重複していた `settings behavior` は `behaviour` の別名にし、古いメモ用の `command_audit` を削除。従来の `settings ...` のパスはすべてそのまま使える
- Create: Aeronautics（Sable）と同時に入れると起動時にクラッシュしていた問題を修正（上流 1.0.6 の修正を移植。Sable が書き換える水流押し出し処理への注入を任意にした）
- 昆布が水位の足りない水にも伸びて満水の水源を作り、水が無限に増えていた問題を修正（満水のマスにだけ伸びる。上流からの移植）
- 部分的に入ったバケツで大釜を満杯にでき、満杯のバケツを汲み直して水が増えていた問題を修正（部分バケツは大釜に入れられない。上流からの移植）
- Create の水車（flow 系モード）は、最近実際に水位が変わったマスだけを流れとして数えるようにした。水位差1で静止した水たまりで水車が永久に回っていた問題を修正（`create_mod_compat water_wheel_flow_window`）。落下する水柱は下向きの流れとして扱い、プレイヤーから遠く流体が止まっている場所では最後の判定を保つ
- Create の水車の回転数が、水車を通る実際の流量で変わるようにした。普通の流れで1倍、強い流れで最大2倍（発電量も比例）、細い流れで0.5倍（`create_mod_compat water_wheel_max_speed_multiplier`、1.0 で従来の固定速度）
- Create のパイプの口が部分的に水の入ったマスへ排水すると、満水の水源で上書きして元の水を消していた問題を修正。8レベルをつながった水へ流し込み、入り切らない時はパイプが詰まる
- Create のからくりが浅い水の上で分解すると、水没可能ブロックが満水扱いになって水が増えていた問題を修正（満水のマスでだけ水没し、それ以外は水を押しのける）
- Create のベイスンで 100mB・250mB などを使うレシピの後、残った水を常に切り捨てて水が減っていた問題を修正（偏りのない確率的丸め）
- Create の扇風機の一括洗浄が、処理1回ごとに一定確率で風路の水を1レベル消費するようにした（`create_mod_compat fan_washing_water_use_chance`、既定 25%）
- 復水器（steam_condenser）を追加。Create の蒸気ボイラーの上に置くと、ボイラーが沸かした水の一部（既定 50%）を内部タンク（4000mB）に水として戻す。ポンプで汲み出してボイラーへ戻せば有限の水でも蒸気機関を回し続けられる（`create_mod_compat condenser_recovery`）
- Create のディスプレイリンクに対応。雨水収集器から雨の強さ・干ばつ指数・地下水、水位センサーから水位や水面までの距離、復水器から復水の量を表示ボードに出せる
- 遊び方プリセットを追加（`settings playstyle create_industrial|realistic_cycle|lightweight_server|relaxed`）。関連する設定だけをまとめて切り替える（上流のプリセット機能をこのフォーク向けに作り直したもの）
- ブロックを水や溶岩の中に置いて押しのけた時のバケツ音を追加（`behaviour displacement_sounds`。上流の押しのけ音設定の移植）


1.0.6
- added adaptive draining and reporting for fluid tick backlogs so chunk initialization, frontier rebuilds, wake queues, and buffered changes clear faster when the server has room instead of piling into delayed lag
- improved connected water slope-search responsiveness so active fronts, pressure-fed water, inlet zones, partial broad surfaces, and nearby short outlets keep enough flow distance to move instead of leaving water stranded behind terrain steps
- widened the lightweight step-down outlet probe default from 1 to 3 blocks while keeping the main slope search capped by existing flow-distance and workload limits
- stabilized ItemPhysic item buoyancy in effective Flowing Fluids water so floating items do not keep a lingering downward velocity and dip back under while rising
- removed nearby-water feedback from water spring emission so springs no longer amplify themselves by reading the surrounding pool as fuel
- clarified water spring behavior as source-fed generation, retired the old nearby-water feedback helpers, and kept surface vent fountains strong with strength-based crests and partial side spray
- protected water spring columns, crests, and nearby spray from infinite-biome thin surface draining so spring-fed water is no longer cleaned up as stray sea-level water
- prevented spring-fed water from being replaced with air by zero-amount fluid writes or remove-all cleanup near water spring sources
- 湧き水の縦坑をハーフブロック・階段などの水没可能ブロックで塞いでも、湧き水の先端からの連結拡散がそのブロックを貫通していた不具合を修正。湧き水由来の水は空気・置換可能ブロック・液体ブロックだけに広がるようにした
- 地表噴出口の真上にブロックを置いても、飛沫がそのブロックの横や上に生成されていた不具合を修正。飛沫は実際に形成された水柱の頂点から出るようにした
- プレイヤーによる水の押しのけを追加。水に入ると沈んだ体積ぶん水面が上がり（全身が沈んだプレイヤーで約5レベル）、水から出ると同じ量を回収するため水は増減しない（`behaviour entity_displacement enable|scale`）
- サイフォンが自分の管内の水を抜いて止まっていた問題を修正し、水源側の水面から吸い出すようにした。流量は落差に比例する線形式から `sqrt(落差 / 損失)` と偏りのない確率的丸めに変更
- 豪雨セル（ゲリラ豪雨）を追加。雨の間、風に流されて移動する雨雲が発生し、発達・最盛期・衰弱の強度曲線とガウス分布の範囲で雨量を最大 `peak_multiplier` 倍にする。風向きは世界ごとの卓越風へ引き戻されるランダムウォーク（Ornstein–Uhlenbeck過程）で変化する。既存の吸水・流出・負荷制限はそのまま適用される（`rain heavy status|start_here|stop|enable|...`）
- 干ばつを強化。晴天が続くと蓄積し雨で回復する連続的な干ばつ指数（0〜1）を追加し、指数に応じて蒸発の強化、一度に蒸発できる水位の引き上げ、極度の干ばつでの池の水面低下、雨の吸い込み、河川干ばつの発動と強化、豪雨セルの抑制を行う（`drying drought enable|set|...`）
- 雨の間は川と海の海面上限（海面の薄い水の除去と海面より上の水の蒸発）を外し、周囲から流れ込む水で増水するようにした。増水中の川は流れが速く、押し流す力も強い。雨が止むと上限は減水期間をかけて徐々に戻る（`rain high_water enable|recession_seconds|...`）
- 押しのけ水位をボートと陸上モブにも拡張（水生生物は対象外、プレイヤーから48ブロック以内、1回64体まで）。台帳は世界に保存され、再起動しても貸し出した水を回収する（`behaviour entity_displacement boats|mobs`）
- 水位センサーを下向きに置くと水位計になり、下の水面までの距離を 15 から引いた強さを出すようにした。雨の間に近くの川や海が海面より1ブロック以上高くなると、アクションバーで増水警報を出す（`rain high_water sensor_depth_gauge|warnings`）
- 雨の間、川と海の水が土砂で濁った茶色になる見た目を追加。バニラ描画とEmbeddiumの両方に対応し、雨の段階が変わった時だけ描画を作り直す（`rain high_water murky_water|murky_strength`）
- 浸食と堆積を追加（既定OFF）。流れの力が素材ごとの限界を超えると川底や岸を削り、土砂は量を保ったまま下流へ運ばれ、流れが緩む所で泥・砂・砂利・粘土として積もる（`rain high_water erosion|erosion_chance|deposition_chance`）
- Serene Seasons との季節連携を追加（入っている場合のみ）。冬は凍結で蒸発と干ばつがほぼ止まり、春は雪解けが増えて雨がなくても川が増水し、夏は乾燥と夕立、秋は長雨が増える（`rain seasons enable|strength|spring_freshet`）
- 地下水を追加。地面にしみ込んだ雨（と行き場のない雪解け水）を64x64の地域ごとの地下水として貯め、地下水位より深く掘った穴（井戸）に地下水をしみ出させる。Create のホースプーリーやポンプで井戸から汲み上げると地下水が減る。地下水は干ばつと夏に減り、冬はほぼ減らない。湧き水の勢いは地下水の量に連動する（`rain groundwater enable|set_here|capacity|...`）
- Create のパイプの口が空になっても、周りにつながった浅い水からまとめて吸い続けるようにした（排水ポンプが洪水の浅い水で止まらない）
- Create のホースプーリーが大きな水域を無限扱いするとき、海面より上の水（増水した川・洪水・山の湖）は有限として実際に減らすようにした
- Create のからくり（水門・扉など）が水の中で閉じた時、置かれたブロックの分の水を消さずに横と上へ押しのけるようにした
- 雪解け水が既存の水を上書きして水を消していた不具合と、氷が残ったまま雪解け水を出し続けることがある不具合を修正
- 干ばつ指数、押しのけ台帳、最後に雨が降った時刻、運搬中の土砂をディメンションごとに保存するようにした
- 稼働中のサイフォンの管を満水のまま保持し、重力流れで管が空になってサイフォンが止まる問題を修正
- 湧き水のtick負荷を削減。地表噴出口の判定を縦坑1回の走査にし、圧力水柱の走査をパルスごとに1回にし、満水の水柱セルを毎パルス書き込み・再起動しないようにした

1.0.5
- added fractional fluid tick delays for water and lava, including 0.5 tick style substeps and 1.5 tick average scheduling
- updated `/flowing_fluids settings behaviour tick_delays__aka__flow_speeds` commands to accept decimal delay values

1.0.4
- fixed severe integrated-server tick stalls caused by repeatedly counting Minecraft's fluid ScheduledTick queue while it was already heavily backlogged
- replaced full scheduled-fluid cleanup scans with a bounded deduplicated cleanup queue so an overloaded tick cannot repeatedly scan the same pending fluid wakeups
- changed rain load shedding to use the mod's own tracked scheduled-fluid count instead of forcing a vanilla queue count on every server tick

1.0.3
- restored the ScheduledTick pressure valve so duplicate fluid wakeups are rejected when Minecraft's fluid tick queue is already heavily backlogged
- added rain load shedding based on recent MSPT and queued fluid tick pressure so extreme rain settings throttle generation and placement instead of creating runaway lag
- reduced save-time stalls from virtual waterlogging by only marking the saved data dirty when the stored fluid actually changes

1.0.2
- fixed cave water springs generating inside sealed stone instead of requiring an existing cave opening
- fixed worldgen spring placement paths that could leave water or lava without the spring block when a later placement check failed
- reduced cave spring clustering by limiting cave wall, floor, and ceiling spring placement to one generated spring per feature pass
- fixed generated lava spring seeding so deep lava springs only fill existing open lava cavities instead of carving hidden lava pockets through solid stone

1.0.1
- fixed world-generation water plugging placing stone or sand too close to ocean and beach surfaces
- fixed surface water vent springs replacing ocean, river, or beach water surfaces with stone-like vent shell blocks
- bumped the Forge 1.20.1 fork version for the terrain-generation bug fix

0.6.1
- fixed a forge crash
- fixed the `concrete_powder_drains_water_chance` setting not updating correctly

0.6
- fixed errors that caused rain to flood the world
- added the `fluid_processing_distance` setting that acts like a "render distance" but for fluid flowing, filling, and draining, behaviours, this can have IMMENSE performance improvements if enabled
- tweaked the conditions for rain and infinite refilling biomes to not cross trigger each other
- rain refilling chance is now forcibly limited to 1/3 of the evaporation chance setting to prevent excessive rain filling
- evaporation now happens even during rain and night time
- rain filling upwards is now disabled by default, this can be enabled with the `rain_fills_block_above` setting
- added the `only_infinite_biomes_at_sea_level` setting which means the infinite water biomes setting (oceans, rivers, swamps etc) will only refill existing water at sea level exactly, this should make draining them easier after the first layer, defaults to false
- added `river_only` and `river_only_opposite` modes for create water wheels
- added the `concrete_powder_drains_water_chance` setting to control the chance that concrete powder will consume water when hardened, defaults to 0.5 (50%)
- rain no longer fills water in deserts

0.5.1
- fixed dedicated servers not correctly sending their config to clients (only the server needs to update)

0.5
- fixed a crash with distant horizons
- fixed Create pipes not consuming fluids in the world
- allowed Create mod pipes to consume fluids in more situations, such as taking water from 2 half filled blocks (still requires a total of 1 full block present)
- added checks to warn users when the wrong Create version is present (on forge this is a controlled crash)
- rain can now fill water in a larger area as long as at least 1 of the blocks has access to the sky
- rain will now fill water twice as fast during a thunderstorm
- added `rain_fills_block_above` setting to allow rain to fill water into empty blocks above, defaults to ON, this means your reservoirs can now overflow
- the evaporation chance setting's default value has been increased from 2% to 5% to help clean up overflows with the above change
- added `min_level_for_obsidian` setting to allow obsidian to be created at lower lava levels, defaults to 6
- made it so blocks that displace fluids are now set per fluid type (e.g. obsidian only doesn't displace lava, ice only doesn't displace water)
- updated API version to 2 due to the above change

0.4
- many settings default values have changed please reset your settings
- added create 0.6 support for both Forge 1.20.1 and NeoForge 1.21.1
- added support for create Hose Pulley blocks
- added the `FlowingFluidsApi` interface to allow other mods to interact with the mod safely, use `FlowingFluidsApi.getInstance(modid)` to get an implementation of the API to use
- changed the farmland drains water setting from on|off to a chance setting `farm_land_drains_water_chance` with values between 0 and 1.0, 0 is off, 1.0 is always
- changed the infinite water biomes feature to be more aggressive, water will now drain back into water in oceans and rivers at sea level much quicker, and will also refill more quickly, as well as the new feature below
- added the `water_wet_biome_non_consume_chance` setting to control the chance that water will not be consumed when flowing from a "wet" biome (oceans, rivers, swamps), this enhances the effect of having these biomes act as infinite sources of water
- added the `animal_breeding_drains_water_chance` setting to control the chance animals may require, and consume, nearby water when breeding. this range is within 8 blocks horizontally and 1 block above or below the animals feet
- added the `plug_fluids_during_world_gen` setting to control if fluids should be plugged during world generation, this is an IMMENSE performance boost as previously these exposed water reservoirs would cause thousands of fluid updates on chunk generation, this won't affect lone fluid blocks unless they are on chunk boundaries
- added the `easy_piston_pumps` setting to delay fluids above a piston from falling back down, to improve upwards piston pumps
- added the `min_level_for_ice` setting to control the minimum level of water required for ice to form, this is useful for making ice  more consistently.
- added further debug commands and removed some
- buckets will now try to place all of their water, and will spread it to nearby water if required, only leaving a partially filled bucket if there was no space.
- all level 1 water now evaporates regardless of exposure to the sky, but still only during the day when it isn't raining
- results of water and lava colliding will now consume 1 level of the fluid that triggered the conversion, e.g. a cobblestone generator will consume 1 level of water as well as replacing the lava
- added a simple fluid pressure system, fluids with fluid above them will spread faster and further per tick, consuming the block above them for extra flow, this pressure only propagates downwards and sideways
- added settings to control whether fluid height differences cause vanilla water pushing behaviours, this can be set separately for boats, players, items, and other entities. Only items entities are enabled by default
- boats will now always float when in, or under, water, this requires the above setting to be off for boats (which is default)
- partially filled buckets will now tint their fullness meter the default color of the fluid
- fixed flowing water texture hiding with sodium
- fixed water level tinting with sodium
- removed the `fast_mode` setting as it was just jank and easily broken

0.3.1 
- neoforge and forge crash fix

0.3
- updated to 1.21.3
- fixed ice displacing the water it is meant to replace
- fixed a sodium / embeddium mixin crash
- removed localization strings in commands text, due to inconsistencies, english "on" and "off" must be used.
- fluid height command changed to behaviour category as it also has non-visual effects
- bug: the debug water level color setting will not work with sodium / embeddium in 1.21.3 due to some workflow delays

0.2

- fixed the flowing texture hiding option not working with sodium
- fixed the sea level being considered 1 level higher than it actually is
- allowed Oceans, Rivers, and Swamps to also absorb water that flows into it at sea level at the exact same rate as the refill setting allows these biomes to infinitely refill to sea level.
- optimized the flowing algorithm, removed a lot of excessive roaming stray water levels would do, this means fluid settles MUCH faster causing fewer updates, this also removes the custom settling behaviour command
- changed some default settings, delete your config file if you want to get the new defaults, or run `/flowing_fluids settings reset_all`
- these changes include altering the vanilla water and lava tick delay rates so they will tick noticeably faster now
- added settings and debug commands:
  - `/flowing_fluids debug kill_all_current_fluid_updates` - kills all current fluid update ticks, useful for debugging
  - `/flowing_fluids debug water_level_tinting <on|off>` - colours water based on the water level, useful for debugging
  - `/flowing_fluids debug super_sponge_at_me <optional fluid block>` - drains water from your position like a sponge block, drains up to 4000 blocks of fluid, useful for debugging
  - `/flowing_fluids settings behaviour waterlogged_blocks_flow_mode <only_in|only_out|in_from_sides_or_top_out_down|ignore>` - controls how fluids flow into or out of water loggable blocks
  - `/flowing_fluids settings behaviour tick_delays <water|lava|lava_nether> <0-255>` - controls the delay between fluid flowing ticks, useful for performance tuning and desired flow speeds
  - `/flowing_fluids settings behaviour flow_distances <water|lava|lava_nether> <1-8>` - controls the distance fluids will search to flow down slopes, useful for performance tuning
  - `/flowing_fluids settings behaviour random_tick_level_check_distance <0-64>` - controls the distance fluids will search during random ticks to check if they can level out, useful for performance tuning
