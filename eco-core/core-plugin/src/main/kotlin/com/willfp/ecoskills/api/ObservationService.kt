package com.willfp.ecoskills.api.observation

import com.willfp.eco.core.Eco
import com.willfp.eco.core.data.keys.PersistentDataKey
import com.willfp.ecoskills.EcoSkillsPlugin
import com.willfp.ecoskills.actionbar.getActionBarObservationKey
import com.willfp.ecoskills.skills.display.getGainSoundObservationKey
import com.willfp.ecoskills.skills.Skills
import com.willfp.ecoskills.stats.Stats
import com.willfp.ecoskills.stats.modifiedStatValue
import com.willfp.ecoskills.stats.peekStatModifierCount
import com.willfp.ecoskills.stats.peekStatModifiers
import com.willfp.ecoskills.effects.Effects
import com.willfp.ecoskills.magic.MagicTypes
import com.willfp.ecoskills.magic.peekObservedMagic
import org.bukkit.Bukkit
import java.util.UUID
import java.util.LinkedHashMap
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import com.willfp.ecoskills.api.observation.ObservationValues.*
import com.willfp.ecoskills.api.observation.ObservationRequest.Section

internal data class Definition(val id:String,val name:String,val key:PersistentDataKey<*>,
    val xpKey:PersistentDataKey<*>?=null,val maxLevel:Int?=null,val requirements:List<Double>?=null,val formula:PureExpression?=null,val hasFormula:Boolean=false)
internal data class MagicDefinition(val id:String,val limit:PureExpression?,val regen:PureExpression?)
internal data class Catalog(val generation:Long,val skills:List<Definition>,val stats:List<Definition>,
    val effects:List<Definition>,val preferences:Map<String,PersistentDataKey<*>>,val magic:List<MagicDefinition>,
    val rankingsEnabled:Boolean,val disabledWorlds:Set<String>,val localStorage:Boolean,
    val access:com.willfp.eco.core.data.observation.ProfileObservationAccess?) {
    val reader = access?.storageReader()
    val valid = skills.size<=128&&stats.size<=128&&effects.size<=128&&magic.size<=32&&
        (skills+stats+effects).all{it.id.length<=64&&it.name.length<=256}&&magic.all{it.id.length<=64}
    val keys:Set<PersistentDataKey<*>> = Collections.unmodifiableSet((skills.flatMap { listOfNotNull(it.key,it.xpKey) }+
        stats.map{it.key}+effects.map{it.key}+preferences.values).toSet())
    val definitions=ObservationService.makeDefinitions(this)
    val capabilities=ObservationService.makeCapabilities(this)
}

/** Captures scalar native definitions on lifecycle, not on observation. */
object ObservationService {
    private val catalog=AtomicReference<Catalog?>()
    private val runtimeId=UUID.randomUUID().toString()
    @Volatile private var stopping=true
    @Volatile private var reads:BoundedReads?=null
    @JvmStatic fun install(plugin:EcoSkillsPlugin) {
        plugin.onReload { publish(plugin) }
        plugin.onEnable {
            reads=BoundedReads()
            stopping=false
            Bukkit.getPluginManager().registerEvents(ObservationSessions(),plugin)
            publish(plugin)
        }
        plugin.onDisable { stopping=true;reads?.close();reads=null;catalog.set(null);ObservationSessions.clear() }
    }
    private fun publish(plugin:EcoSkillsPlugin) {
        val generation=(catalog.get()?.generation?:0)+1
        val c=Catalog(generation,
            Skills.values().map{Definition(it.id,it.name,it.getObservationKey(),it.getObservationXPKey(),it.maxLevel,it.getObservationRequirements(),PureExpression.prepare(it.getObservationFormula(),Eco.get().javaClass.classLoader),it.getObservationFormula()!=null)},
            Stats.values().map{Definition(it.id,it.name,it.getObservationKey())},
            Effects.values().map{Definition(it.id,it.name,it.getObservationKey())},
            mapOf("actionBarEnabled" to getActionBarObservationKey(),"gainSoundEnabled" to getGainSoundObservationKey()),
            MagicTypes.values().map{MagicDefinition(it.id,PureExpression.prepare(it.getObservationLimitExpression(),Eco.get().javaClass.classLoader),PureExpression.prepare(it.getObservationRegenExpression(),Eco.get().javaClass.classLoader))},
            plugin.configYml.getBool("leaderboard.enabled"),plugin.configYml.getStrings("disabled-in-worlds").map{it.lowercase()}.toSet(),
            plugin.isUsingLocalStorage,Eco.get().profileObservationAccess)
        catalog.set(c)
    }
    private fun main() { check(Bukkit.isPrimaryThread()) { "Cached observation requires the main thread" } }
    private fun unavailable(uuid:UUID,reason:String,source:String="CACHE")=PlayerObservation(map(
        "schemaVersion",2,"playerUuid",uuid.toString(),"source",source,"observedAt",System.currentTimeMillis(),
        "runtimeId",runtimeId,"status","unavailable","reason",reason))
    @JvmStatic fun cached(uuid:UUID):PlayerObservation=cached(uuid,ObservationRequest.all())
    @JvmStatic fun cached(uuid:UUID,request:ObservationRequest):PlayerObservation {
        main();val c=catalog.get()?:return unavailable(uuid,"service_unavailable")
        if(stopping)return unavailable(uuid,"service_stopping")
        if(!c.valid)return unavailable(uuid,"limit_exceeded")
        val access=c.access?:return unavailable(uuid,"cache_api_unavailable")
        val started=System.currentTimeMillis()
        val read=access.peek(uuid,c.keys)
        val player=Bukkit.getPlayer(uuid)
        val mods=if(player!=null)peekStatModifiers(uuid) else null
        val modifiers=mods?.map{map("uuid",it.uuid.toString(),"statId",it.stat.id,"operation",it.operation.name,"amount",it.modifier)}
        val nativeValues=LinkedHashMap<String,Double>()
        if(mods!=null&&mods.size<=512)for(d in c.stats){
            val base=read.values[d.key] as? Int ?: continue
            nativeValues[d.id]=modifiedStatValue(base.toDouble(),mods.filter{it.stat.id==d.id})
        }
        val currentMagic=LinkedHashMap<String,Int>()
        if(player!=null)for(type in MagicTypes.values()) {
            if(ObservationSessions.hasCurrentMagic(player,type.id))peekObservedMagic(uuid,type)?.let{currentMagic[type.id]=it}
        }
        return try { assemble(uuid,c,read.values,read.loaded,"CACHE",request,modifiers,currentMagic,
            player?.world?.name?.lowercase()?.let{it in c.disabledWorlds},modifierTotal=if(player!=null)peekStatModifierCount(uuid) else null,effectiveValues=nativeValues,startedAt=started,completedAt=System.currentTimeMillis()) } catch(e:IllegalArgumentException) { unavailable(uuid,"limit_exceeded") }
    }
    @JvmStatic fun stored(uuid:UUID,request:ObservationRequest):java.util.concurrent.CompletableFuture<PlayerObservation> {
        val batch=storedBatch(setOf(uuid),request)
        val view=java.util.concurrent.CompletableFuture<PlayerObservation>()
        batch.whenComplete { result,error -> if(error!=null)view.completeExceptionally(error) else view.complete(result.getValue(uuid)) }
        view.whenComplete { _,_ -> if(view.isCancelled)batch.cancel(false) }
        return view
    }
    @JvmStatic fun storedBatch(uuids:Set<UUID>,request:ObservationRequest):java.util.concurrent.CompletableFuture<Map<UUID,PlayerObservation>> {
        require(uuids.isNotEmpty()&&uuids.size<=16&&uuids.none{java.util.Objects.isNull(it)}) { "Batch requires 1-16 distinct UUIDs" }
        val ids=Collections.unmodifiableSet(LinkedHashSet(uuids))
        val c=catalog.get()
        fun failure(reason:String)=Collections.unmodifiableMap(ids.associateWith{uuid ->
            if(c==null||!c.valid)unavailable(uuid,reason,"STORAGE") else {
                val data=assemble(uuid,c,emptyMap(),false,"STORAGE",request,readReason=reason).asMap().toMutableMap()
                data["status"]="unavailable";data["reason"]=reason;PlayerObservation(data)
            }
        })
        if(stopping)return java.util.concurrent.CompletableFuture.completedFuture(failure("service_stopping"))
        if(c==null)return java.util.concurrent.CompletableFuture.completedFuture(failure("service_unavailable"))
        if(!c.valid)return java.util.concurrent.CompletableFuture.completedFuture(failure("limit_exceeded"))
        val executor=reads?:return java.util.concurrent.CompletableFuture.completedFuture(failure("service_stopping"))
        val keys=LinkedHashSet<com.willfp.eco.core.data.keys.PersistentDataKey<*>>()
        if(request.includes(Section.SKILLS))c.skills.forEach{keys.add(it.key);it.xpKey?.let(keys::add)}
        if(request.includes(Section.STATS))c.stats.forEach{keys.add(it.key)}
        if(request.includes(Section.EFFECTS))c.effects.forEach{keys.add(it.key)}
        if(request.includes(Section.PREFERENCES))keys.addAll(c.preferences.values)
        val unsupported=keys.filter{c.localStorage||it.isLocal||c.reader==null}.toSet()
        keys.removeAll(unsupported)
        val requestedAt=System.currentTimeMillis()
        return executor.submit(listOf(ids,request,c.generation), { control ->
            val started=System.currentTimeMillis()
            val values=if(keys.isEmpty())emptyMap() else c.reader!!.read(ids,Collections.unmodifiableSet(keys),control)
            val completed=System.currentTimeMillis()
            control.check()
            if(stopping)return@submit failure("service_stopping")
            if(catalog.get()!==c)return@submit failure("registry_changed")
            val result=ids.associateWith { uuid ->
                val data=assemble(uuid,c,values[uuid]?:emptyMap(),false,"STORAGE",request,
                    keyReasons=unsupported.associateWith{"unsupported_backend"},startedAt=started,completedAt=completed,requestedAt=requestedAt).asMap()
                PlayerObservation(data)
            }
            Collections.unmodifiableMap(result)
        }, ::failure)
    }

    internal fun assemble(uuid:UUID,c:Catalog,values:Map<PersistentDataKey<*>,Any>,loaded:Boolean,
        source:String,request:ObservationRequest,mods:List<Map<String,Any>>?=null,magic:Map<String,Int> = emptyMap(),
        restricted:Boolean?=null,readReason:String?=null,keyReasons:Map<PersistentDataKey<*>,String> = emptyMap(),modifierTotal:Int?=mods?.size,effectiveValues:Map<String,Double>?=null,startedAt:Long?=null,completedAt:Long?=null,requestedAt:Long?=null):PlayerObservation {
        val cold=readReason?:if(source=="STORAGE")"key_not_stored" else if(loaded)"key_not_cached" else "profile_not_loaded"
        fun value(key:PersistentDataKey<*>)=values[key]
        val fieldSource=if(source=="STORAGE")"STORED" else source
        fun reason(key:PersistentDataKey<*>)=keyReasons[key]?:cold
        fun readField(key:PersistentDataKey<*>):Map<String,Any?> {
            val v=value(key)?:return missing(reason(key))
            val valid=when(key.type){com.willfp.eco.core.data.keys.PersistentDataKeyType.INT->v is Int;com.willfp.eco.core.data.keys.PersistentDataKeyType.DOUBLE->v is Double&&v.isFinite();com.willfp.eco.core.data.keys.PersistentDataKeyType.BOOLEAN->v is Boolean;else->true}
            return if(valid)field(v,fieldSource) else missing("invalid_value")
        }
        val root=map("schemaVersion",2,"playerUuid",uuid.toString(),"source",source,"observedAt",System.currentTimeMillis(),
            "runtimeId",runtimeId,"catalogGeneration",c.generation,"logicalAtomicity","not_guaranteed",
            "coherence","best_effort","storageRoutingGeneration",c.generation,"scope",if(source=="CACHE")"NODE_LOCAL" else if(c.localStorage||c.reader==null)"UNKNOWN" else "SHARED","requestedAt",requestedAt,"readStartedAt",startedAt?:if(source=="CACHE")System.currentTimeMillis() else null,"readCompletedAt",completedAt?:if(source=="CACHE")System.currentTimeMillis() else null,"persistenceEvidence",if(source=="CACHE")"unknown" else "stored_values")
        root["session"]=map("present",if(source=="CACHE")field(mods!=null,"SESSION") else missing("not_retained"),"scope","NODE_LOCAL")
        if(request.includes(Section.SKILLS)) {
            var known=0;var total=0L;var maximum=0
            val items=c.skills.map { d ->
                val level=(value(d.key) as? Int)?.takeIf{it>=0}
                val xp=(d.xpKey?.let{value(it)} as? Number)?.toDouble()?.takeIf{it>=0&&it.isFinite()}
                val atMax=level?.let{it>=d.maxLevel!!}
                val required=if(level!=null&&atMax==false)(if(d.hasFormula)d.formula?.evaluate(mapOf("%level%" to kotlin.math.max(1,level).toDouble())) else d.requirements?.getOrNull(level))?.takeIf{it.isFinite()&&it>0} else null
                if(level!=null){known++;total+=level;if(atMax==true)maximum++}
                map("id",d.id,"level",if(level!=null)field(level,fieldSource) else missing(if(value(d.key)!=null)"invalid_value" else reason(d.key)),
                    "xpWithinLevel",if(xp!=null)field(xp,fieldSource) else missing(if(d.xpKey?.let{value(it)}!=null)"invalid_value" else d.xpKey?.let{reason(it)}?:cold),
                    "maxLevel",field(d.maxLevel,"DEFINITION"),
                    "atMaxLevel",atMax?.let{field(it,"DERIVED_FROM_OBSERVED_INPUTS")}?:missing(cold),
                    "requiredXp",required?.let{field(it,"DERIVED_FROM_OBSERVED_INPUTS")}?:missing(if(atMax==true)"at_max_level" else if(level==null)cold else "unsupported_expression"),
                    "progressPercent",if(xp!=null&&required!=null)field(xp/required*100,"DERIVED_FROM_OBSERVED_INPUTS") else missing(if(atMax==true)"at_max_level" else "missing_inputs"),
                    "progress",if(xp!=null&&required!=null)field(xp/required,"DERIVED_FROM_OBSERVED_INPUTS") else missing(if(atMax==true)"at_max_level" else "missing_inputs"),
                    "xpToNextLevel",if(xp!=null&&required!=null)field(kotlin.math.max(0.0,required-xp),"DERIVED_FROM_OBSERVED_INPUTS") else missing(if(atMax==true)"at_max_level" else "missing_inputs"))
            }
            root["skills"]=map("items",items,"knownCount",known,"unknownCount",c.skills.size-known,
                "knownLevelSubtotal",total,"totalSkillLevel",if(known==c.skills.size)field(total,"DERIVED_FROM_OBSERVED_INPUTS") else missing("missing_inputs"),
                "averageSkillLevel",if(known==c.skills.size&&known>0)field(total.toDouble()/known,"DERIVED_FROM_OBSERVED_INPUTS") else missing("missing_inputs"),
                "knownAtMaxCount",maximum)
        }
        val effectiveStats=LinkedHashMap<String,Double>()
        if(request.includes(Section.STATS)||request.includes(Section.MAGIC)) {
            val statItems=c.stats.map {d ->
                val base=value(d.key) as? Int
                var effective=if(effectiveValues!=null)effectiveValues[d.id] else base?.toDouble()
                val specific=mods?.filter{it["statId"]==d.id}
                if(effectiveValues!=null) { /* Native pure calculation was captured from the same cached inputs. */ }
                else if(effective!=null&&specific!=null&&mods.size<=512) {
                    for(m in specific)if(m["operation"]=="ADD")effective=effective!!+(m["amount"] as Number).toDouble()
                    for(m in specific)if(m["operation"]=="MULTIPLY")effective=effective!!*(m["amount"] as Number).toDouble()
                } else effective=null
                effective?.takeIf{it.isFinite()}?.let{effectiveStats["%ecoskills_${d.id}%"]=it}
                map("id",d.id,"base",readField(d.key),
                    "modified",effective?.let{field(it,"DERIVED_FROM_OBSERVED_INPUTS")}?:missing(if(base==null)cold else if(mods!=null&&mods.size>512)"limit_exceeded" else "session_required"),
                    "bonus",effective?.let{field(it-base!!,"DERIVED_FROM_OBSERVED_INPUTS")}?:missing(if(source=="STORAGE")"session_required" else "missing_inputs"),
                    "legacy",effective?.takeIf{it.isFinite()}?.let{field(it.toInt(),"DERIVED_FROM_OBSERVED_INPUTS")}?:missing(if(source=="STORAGE")"session_required" else "missing_inputs"),
                    "modifierCount",if(specific!=null&&mods.size<=512)field(specific.size,"SESSION") else missing(if(mods!=null)"limit_exceeded" else "session_required"))
            }
            if(request.includes(Section.STATS))root["stats"]=map("items",statItems)
        }
        if(request.includes(Section.EFFECTS))root["effects"]=map("items",c.effects.map{map("id",it.id,"level",readField(it.key))})
        if(request.includes(Section.PREFERENCES))root["preferences"]=c.preferences.mapValues{readField(it.value)}
        if(request.includes(Section.MODIFIERS))root["modifiers"]=if(mods!=null)map("status",if(mods.size<=512)"available" else "partial","evaluationOrder","ADD_THEN_MULTIPLY_IN_RETAINED_ORDER","items",mods.take(512),"total",modifierTotal,"returned",kotlin.math.min(512,mods.size),"truncated",mods.size>512) else map("status","unavailable","reason","session_required","items",emptyList<Any>())
        if(request.includes(Section.MAGIC))root["magic"]=map("items",c.magic.map { d ->
            val current=magic[d.id]
            val limit=if(current!=null)d.limit?.evaluate(effectiveStats)?.toInt() else null
            val context=LinkedHashMap(effectiveStats)
            if(limit!=null)context["%ecoskills_${d.id}_limit%"] = limit.toDouble()
            val regen=if(current!=null)d.regen?.evaluate(context)?.let{kotlin.math.ceil(it).toInt()} else null
            map("id",d.id,"current",current?.let{field(it,"SESSION")}?:missing("session_required"),
                "limit",limit?.let{field(it,"DERIVED_FROM_OBSERVED_INPUTS")}?:missing(if(current==null)"session_required" else "missing_inputs"),
                "baseRegenPerSecond",regen?.let{field(it,"DERIVED_FROM_OBSERVED_INPUTS")}?:missing(if(current==null)"session_required" else "missing_inputs"),
                "lastActualRegen",missing("not_retained"))
        })
        if(request.includes(Section.RANKINGS))root["rankings"]=map("enabled",c.rankingsEnabled,"status","unavailable","reason",if(c.rankingsEnabled)"not_materialized" else "disabled","totalRank",missing(if(c.rankingsEnabled)"not_materialized" else "disabled"),"skills",c.skills.map{map("id",it.id,"rank",missing(if(c.rankingsEnabled)"not_materialized" else "disabled"))},"asOf",missing("not_retained"))
        if(request.includes(Section.CONTEXT))root["context"]=map("worldRestricted",restricted?.let{field(it,"SESSION")}?:missing("session_required"),"xpMultiplier",missing("not_retained"),"xpEligible",missing("not_retained"),"effectiveActionBar",missing("not_retained"))
        if(request.includes(Section.PRESENTATION))root["presentation"]=map("status","unavailable","reason","not_materialized","actionBarLock",missing("not_materialized"),"recentXp",missing("not_materialized"),"pendingSound",missing("not_materialized"),"bossbar",missing("not_retained"))
        if(request.includes(Section.HISTORICAL))root["historical"]=map("status","unavailable","reason","unsupported")
        fun complete(v:Any?):Boolean=when(v){is Map<*,*>->(v["reason"]=="at_max_level"|| (source=="STORAGE"&&request==ObservationRequest.persistentOnly()&&v["reason"]=="session_required"))|| (v["status"]!="unavailable"&&v["status"]!="partial"&&v.values.all{complete(it)});is Iterable<*>->v.all{complete(it)};else->true}
        root["status"]=if(complete(root.filterKeys{it!="session"}))"available" else "partial"
        if(!loaded&&source=="CACHE")root["reason"]="profile_not_loaded"
        return try{PlayerObservation(root)}catch(e:IllegalArgumentException){unavailable(uuid,"limit_exceeded",source)}
    }
    @JvmStatic fun legacy(uuid:UUID):Map<String,Any?> {
        main();return try{legacyProjection(uuid)}catch(e:IllegalArgumentException){legacyAbsent(uuid,"limit_exceeded")}
    }
    private fun legacyProjection(uuid:UUID):Map<String,Any?> {
        main();val c=catalog.get()
        if(c==null||stopping)return legacyAbsent(uuid,"service_unavailable")
        if(!c.valid)return legacyAbsent(uuid,"limit_exceeded")
        val access=c.access?:return legacyAbsent(uuid,"cache_api_unavailable")
        val read=access.peek(uuid,c.keys)
        if(!read.loaded)return legacyAbsent(uuid,"profile_not_loaded")
        val full=cached(uuid,ObservationRequest(setOf(Section.SKILLS,Section.STATS))).asMap()
        if(full["status"]=="unavailable")return legacyAbsent(uuid,full["reason"] as? String ?: "service_unavailable")
        fun number(row:Map<*,*>,name:String):Any?=(row[name] as? Map<*,*>)?.get("value")
        val skills=((full["skills"] as? Map<*,*>)?.get("items") as? List<*>)?.map {v ->val row=v as Map<*,*>;map("id",row["id"],"level",number(row,"level"),"xpWithinLevel",number(row,"xpWithinLevel"),"requiredXp",number(row,"requiredXp"),"maxLevel",number(row,"maxLevel"))}?:emptyList()
        val stats=((full["stats"] as? Map<*,*>)?.get("items") as? List<*>)?.map{v ->val row=v as Map<*,*>;map("id",row["id"],"base",number(row,"base"),"modified",number(row,"modified"))}?:emptyList()
        val complete=skills.all{it["level"]!=null&&it["xpWithinLevel"]!=null&&it["maxLevel"]!=null}&&stats.all{it["base"]!=null&&it["modified"]!=null}
        @Suppress("UNCHECKED_CAST") return ObservationValues.immutableLegacy(map("schemaVersion",1,"playerUuid",uuid.toString(),"observedAt",full["observedAt"],"status",if(complete)"available" else "partial","reason",if(complete)null else "cache_partial","skills",skills,"stats",stats)) as Map<String,Any?>
    }
    private fun legacyAbsent(uuid:UUID,reason:String):Map<String,Any?> = ObservationValues.immutableLegacy(map("schemaVersion",1,"playerUuid",uuid.toString(),"observedAt",System.currentTimeMillis(),"status","unavailable","reason",reason,"skills",emptyList<Any>(),"stats",emptyList<Any>()))
    @JvmStatic fun definitions():EcoSkillsDefinitions = catalog.get()?.definitions?:EcoSkillsDefinitions(map("status","unavailable","reason","service_unavailable"))
    internal fun makeDefinitions(c:Catalog):EcoSkillsDefinitions = try{definitionsProjection(c)}catch(e:IllegalArgumentException){EcoSkillsDefinitions(map("status","unavailable","reason","limit_exceeded"))}
    private fun definitionsProjection(c:Catalog):EcoSkillsDefinitions {
        if(!c.valid)return EcoSkillsDefinitions(map("status","unavailable","reason","limit_exceeded"))
        return EcoSkillsDefinitions(map("schemaVersion",2,"catalogGeneration",c.generation,
            "skills",c.skills.map{map("id",it.id,"name",it.name,"maxLevel",it.maxLevel,"requirements",it.requirements?.take(512),"requirementsTruncated",(it.requirements?.size?:0)>512,"formulaSupported",it.formula!=null)},
            "stats",c.stats.map{map("id",it.id,"name",it.name,"unit","stat_points")},
            "effects",c.effects.map{map("id",it.id,"name",it.name)},"magicTypes",c.magic.map{map("id",it.id,"limitSupported",it.limit!=null,"regenSupported",it.regen!=null,"limitInputs",it.limit?.variables(),"regenInputs",it.regen?.variables())}))
    }
    @JvmStatic fun capabilities()=catalog.get()?.capabilities?:makeCapabilities(null)
    internal fun makeCapabilities(c:Catalog?)=ObservationCapabilities(map("schemaVersion",2,"cache",c?.access!=null,"storage",c?.reader!=null&&c.localStorage==false,"implementedStorageBackends",listOf("mysql"),"completionThread","READ_OR_DEADLINE; immediate rejection on caller",
        "cachedThread","MAIN","legacySchemaVersion",1,"fullSchemaVersion",2,"maxBatch",16,"maxConcurrentReads",2,"maxQueuedReads",64,"deadlineMillis",2000,"reservedPoolConnections",1,"rankings","not_materialized","presentation","not_materialized","history","unsupported"))
}
