package com.livepets3d.app.data

/** Visual building blocks used by the procedural 3D rig. */
enum class EarType { POINTY, FLOPPY, ROUND, LONG, HORNS }
enum class TailType { THIN_LONG, BUSHY, FLUFFY, POM, DRAGON }
enum class PetCategory { DOMESTIC, WILD, FANTASY }

/** Parameters for the 3D look of an animal. Colours are 0xRRGGBB. */
data class Look(
    val furLength: Float,
    val primary: Int,
    val secondary: Int,          // muzzle / chest / paws
    val accent: Int,             // stripes, patches, ear tips
    val inner: Int,              // inner ear / mouth
    val eye: Int,
    val nose: Int,
    val ear: EarType,
    val tail: TailType,
    val stripes: Float = 0f,     // stripe frequency (0 = none)
    val legColor: Int = primary,
    val pawColor: Int = secondary,
    val eyePatches: Boolean = false,
    val collar: Boolean = false,
    val wings: Boolean = false,
    val scales: Boolean = false,
    val tongue: Boolean = false,
    val headScale: Float = 1f,
    val bodyScale: Float = 1f,
    val eyeScale: Float = 1f,
    val tailTipColor: Int = -1,
    val canClimb: Boolean = true,
)

/**
 * Full catalogue (24 animals from the master spec). Only species with [look] != null are
 * implemented in this build; the rest are visible as "coming soon" so the architecture,
 * store and progression already account for them.
 */
enum class PetSpecies(
    val displayName: String,
    val defaultName: String,
    val category: PetCategory,
    val sound: String,
    val premium: Boolean,
    val look: Look?,
) {
    KITTEN("Orange Kitten", "Mimi", PetCategory.DOMESTIC, "meow", false, Look(
        furLength = 0.075f, primary = 0xF0963F, secondary = 0xFFF3E2, accent = 0xC8621E,
        inner = 0xF7A1B0, eye = 0x7CC44C, nose = 0xF08A9A, ear = EarType.POINTY,
        tail = TailType.THIN_LONG, stripes = 9f, collar = true, eyeScale = 1.05f)),
    PUPPY("Golden Puppy", "Buddy", PetCategory.DOMESTIC, "bark", false, Look(
        furLength = 0.09f, primary = 0xE6B172, secondary = 0xF8E2BE, accent = 0xC98D4E,
        inner = 0xE9798A, eye = 0x4A2A14, nose = 0x231A17, ear = EarType.FLOPPY,
        tail = TailType.FLUFFY, collar = true, tongue = true, headScale = 1.03f)),
    PANDA("Baby Panda", "Bao", PetCategory.WILD, "panda", false, Look(
        furLength = 0.095f, primary = 0xF5F4F0, secondary = 0xFFFFFF, accent = 0x1E1E24,
        inner = 0xE07A8A, eye = 0x3C7DD9, nose = 0x1A1A1F, ear = EarType.ROUND,
        tail = TailType.POM, legColor = 0x1E1E24, pawColor = 0x1E1E24, eyePatches = true,
        bodyScale = 1.08f, collar = true)),
    RABBIT("Fluffy Rabbit", "Coco", PetCategory.DOMESTIC, "bunny", false, Look(
        furLength = 0.085f, primary = 0xF4F0EC, secondary = 0xFFFFFF, accent = 0xD9CFC6,
        inner = 0xF5A3B5, eye = 0x2B1D2E, nose = 0xF28DA2, ear = EarType.LONG,
        tail = TailType.POM, canClimb = false, collar = true)),
    FOX("Baby Fox", "Rusty", PetCategory.WILD, "fox", false, Look(
        furLength = 0.085f, primary = 0xF2782C, secondary = 0xFFF6EC, accent = 0x2A1C18,
        inner = 0x3A2620, eye = 0xE7A23A, nose = 0x221816, ear = EarType.POINTY,
        tail = TailType.BUSHY, legColor = 0xB9551E, pawColor = 0x2A1C18, tailTipColor = 0xFFF6EC,
        headScale = 1.02f, collar = true)),
    DRAGON("Baby Dragon", "Spark", PetCategory.FANTASY, "dragon", true, Look(
        furLength = 0f, primary = 0x8B5CF6, secondary = 0xFFD27A, accent = 0x5B32C8,
        inner = 0xB794FF, eye = 0xFFB31F, nose = 0x4C2A9E, ear = EarType.HORNS,
        tail = TailType.DRAGON, wings = true, scales = true, eyeScale = 1.08f)),

    // ---- Roadmap (declared, not yet modelled) ----
    PERSIAN("White Persian", "Snow", PetCategory.DOMESTIC, "meow", false, null),
    BRITISH("British Shorthair", "Earl", PetCategory.DOMESTIC, "meow", false, null),
    HUSKY("Husky Puppy", "Storm", PetCategory.DOMESTIC, "bark", false, null),
    FRENCHIE("French Bulldog", "Pepper", PetCategory.DOMESTIC, "bark", false, null),
    HAMSTER("Hamster", "Nugget", PetCategory.DOMESTIC, "bunny", false, null),
    RED_PANDA("Red Panda", "Maple", PetCategory.WILD, "panda", false, null),
    LION("Baby Lion", "Leo", PetCategory.WILD, "dragon", true, null),
    TIGER("Baby Tiger", "Raja", PetCategory.WILD, "dragon", true, null),
    ELEPHANT("Baby Elephant", "Peanut", PetCategory.WILD, "panda", true, null),
    KOALA("Koala", "Kiko", PetCategory.WILD, "panda", false, null),
    PENGUIN("Penguin", "Pingu", PetCategory.WILD, "bunny", false, null),
    MONKEY("Baby Monkey", "Momo", PetCategory.WILD, "fox", false, null),
    TURTLE("Baby Turtle", "Shelly", PetCategory.WILD, "bunny", false, null),
    DINO("Baby Dinosaur", "Rex", PetCategory.FANTASY, "dragon", true, null),
    UNICORN("Unicorn", "Stella", PetCategory.FANTASY, "chime", true, null),
    MAGIC_FOX("Magical Fox", "Aurora", PetCategory.FANTASY, "fox", true, null),
    ICE_DRAGON("Ice Dragon", "Frost", PetCategory.FANTASY, "dragon", true, null),
    FIRE_DRAGON("Fire Dragon", "Blaze", PetCategory.FANTASY, "dragon", true, null);

    val available: Boolean get() = look != null

    companion object {
        val playable: List<PetSpecies> get() = values().filter { it.available }
        fun byName(n: String?): PetSpecies = values().firstOrNull { it.name == n && it.available } ?: KITTEN
    }
}
