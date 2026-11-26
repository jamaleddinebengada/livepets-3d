package com.livepets3d.app.data

/** Visual building blocks used by the procedural 3D rig. */
enum class EarType { POINTY, FLOPPY, ROUND, LONG, HORNS, NONE }
enum class TailType { THIN_LONG, BUSHY, FLUFFY, POM, DRAGON, NONE }
enum class PetCategory { DOMESTIC, WILD, FANTASY }
enum class BodyType { QUADRUPED, PENGUIN }

/** Parameters for the 3D look of an animal. Colours are 0xRRGGBB. */
data class Look(
    val furLength: Float,
    val primary: Int,
    val secondary: Int,          // muzzle / chest / paws / belly
    val accent: Int,             // stripes, patches, ear tips
    val inner: Int,              // inner ear
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
    val body: BodyType = BodyType.QUADRUPED,
    /** White eye sclera visible around a smaller iris (puppy / fox / penguin references). */
    val sclera: Boolean = false,
    /** Eyebrow colour, -1 = none. */
    val brows: Int = -1,
    /** Resting open-mouth smile (0..1). */
    val smile: Float = 0f,
    val earScale: Float = 1f,
    val legLength: Float = 1f,
)

/** Behaviour weights that give each species its own personality. */
data class Personality(
    val walkSpeed: Float = 1f,
    val energy: Float = 1f,       // how often it runs / plays
    val sleepy: Float = 1f,       // how often it naps
    val social: Float = 1f,       // waves / happy reactions
    val special: List<String> = emptyList(),
)

/** A colour variant. Prices: 0 = free, >0 coins, <0 gems (absolute value). */
data class Skin(val id: String, val name: String, val primary: Int, val secondary: Int, val accent: Int, val price: Int = 0, val leg: Int = -1)

/**
 * Full catalogue. Only species with [look] != null are implemented in this build; the rest
 * are shown as "coming soon" so store and progression already account for them.
 */
enum class PetSpecies(
    val displayName: String,
    val defaultName: String,
    val category: PetCategory,
    val sound: String,
    val premium: Boolean,
    val look: Look?,
    val personality: Personality = Personality(),
    val skins: List<Skin> = emptyList(),
    /** Asset folder of a sprite-based character (assets/sprites/<id>); null = procedural 3D. */
    val sprite: String? = null,
) {
    KITTEN("Orange Kitten", "Mimi", PetCategory.DOMESTIC, "meow", false, Look(
        furLength = 0.07f, primary = 0xF29A3E, secondary = 0xFFEBCF, accent = 0xD9772A,
        inner = 0xF5A6A6, eye = 0x5A3410, nose = 0xE9877F, ear = EarType.POINTY,
        tail = TailType.THIN_LONG, stripes = 7f, collar = true, eyeScale = 1.1f, smile = 0.45f,
        tongue = true, earScale = 1.3f, headScale = 1.04f),
        Personality(walkSpeed = 1.1f, energy = 1.3f, sleepy = 1f, special = listOf("tailchase", "chase", "pounce")),
        listOf(
            Skin("ginger", "Ginger", 0xF29A3E, 0xFFEBCF, 0xD9772A),
            Skin("grey", "Silver Tabby", 0xA9ADB8, 0xF1F1F4, 0x6B7080, 150),
            Skin("cream", "Cream", 0xF3DDB8, 0xFFF8EE, 0xE2BE8C, 150),
            Skin("night", "Midnight", 0x3A3346, 0xE9E4F2, 0x1F1A28, -15),
        )),
    PUPPY("Golden Puppy", "Buddy", PetCategory.DOMESTIC, "bark", false, Look(
        furLength = 0.07f, primary = 0xF0B868, secondary = 0xFBE7C4, accent = 0xE3A04D,
        inner = 0xE9798A, eye = 0x7A3F12, nose = 0x3A2620, ear = EarType.FLOPPY,
        tail = TailType.FLUFFY, collar = true, tongue = true, headScale = 1.06f,
        sclera = true, brows = 0xF7D49A, smile = 0.6f, earScale = 1.1f),
        Personality(walkSpeed = 1.15f, energy = 1.4f, sleepy = 0.9f, social = 1.6f, special = listOf("wag", "sit")),
        listOf(
            Skin("golden", "Golden", 0xF0B868, 0xFBE7C4, 0xE3A04D),
            Skin("choco", "Chocolate", 0x7B4A2D, 0xC99A72, 0x5C3420, 150),
            Skin("snow", "Snowy", 0xF4EFE8, 0xFFFFFF, 0xE2D7C8, 150),
            Skin("dalmatian", "Spotty", 0xF7F4EE, 0xFFFFFF, 0x2B2B2B, -15),
        ), sprite = "puppy"),
    PENGUIN("Baby Penguin", "Pingu", PetCategory.WILD, "penguin", false, Look(
        furLength = 0.03f, primary = 0x1F2E4D, secondary = 0xF4E9D2, accent = 0x1A2640,
        inner = 0xF4E9D2, eye = 0x4A2716, nose = 0xFF8A24, ear = EarType.NONE,
        tail = TailType.NONE, body = BodyType.PENGUIN, sclera = true, brows = 0x1A2640,
        canClimb = false, eyeScale = 1.05f),
        Personality(walkSpeed = 0.75f, energy = 1.1f, sleepy = 1f, social = 1.2f, special = listOf("slide", "waddle")),
        listOf(
            Skin("navy", "Classic Navy", 0x1F2E4D, 0xF4E9D2, 0x1A2640),
            Skin("emperor", "Emperor Grey", 0x8E97A6, 0xFFFFFF, 0x4B5260, 150),
            Skin("rose", "Rosy", 0x8E4A6E, 0xFBE8EE, 0x6A3352, -15),
        )),
    FOX("Baby Fox", "Rusty", PetCategory.WILD, "fox", false, Look(
        furLength = 0.075f, primary = 0xF26A1B, secondary = 0xFFFFFF, accent = 0x1E1A1C,
        inner = 0xF3EEEA, eye = 0x9A5512, nose = 0x161214, ear = EarType.POINTY,
        tail = TailType.BUSHY, legColor = 0x1E1A1C, pawColor = 0x1E1A1C, tailTipColor = 0xFFFFFF,
        headScale = 1.03f, collar = false, sclera = true, brows = 0x4A3A36, smile = 0f, earScale = 1.45f,
        legLength = 1.1f),
        Personality(walkSpeed = 1.25f, energy = 1.3f, sleepy = 0.8f, special = listOf("explore", "stretch")),
        listOf(
            Skin("red", "Red Fox", 0xF26A1B, 0xFFFFFF, 0x1E1A1C, leg = 0x1E1A1C),
            Skin("arctic", "Arctic", 0xF2F4F8, 0xFFFFFF, 0xB8C2D6, 200, leg = 0xDCE2EC),
            Skin("silver", "Silver", 0x5B6170, 0xE8EAF0, 0x1E1F26, -20, leg = 0x1E1F26),
        )),
    PANDA("Baby Panda", "Bao", PetCategory.WILD, "panda", false, Look(
        furLength = 0.095f, primary = 0xF5F4F0, secondary = 0xFFFFFF, accent = 0x1E1E24,
        inner = 0xE07A8A, eye = 0x3C7DD9, nose = 0x1A1A1F, ear = EarType.ROUND,
        tail = TailType.POM, legColor = 0x1E1E24, pawColor = 0x1E1E24, eyePatches = true,
        bodyScale = 1.08f, collar = true),
        Personality(walkSpeed = 0.6f, energy = 0.7f, sleepy = 1.6f, special = listOf("bamboo", "roll")),
        listOf(
            Skin("classic", "Classic", 0xF5F4F0, 0xFFFFFF, 0x1E1E24, leg = 0x1E1E24),
            Skin("qinling", "Brown Panda", 0xEDE3D6, 0xFFFFFF, 0x6B4A35, 200, leg = 0x6B4A35),
        )),
    RABBIT("Fluffy Rabbit", "Coco", PetCategory.DOMESTIC, "bunny", false, Look(
        furLength = 0.085f, primary = 0xF4F0EC, secondary = 0xFFFFFF, accent = 0xD9CFC6,
        inner = 0xF5A3B5, eye = 0x2B1D2E, nose = 0xF28DA2, ear = EarType.LONG,
        tail = TailType.POM, canClimb = false, collar = true),
        Personality(walkSpeed = 0.9f, energy = 1.1f, special = listOf("hop"))),
    DRAGON("Baby Dragon", "Spark", PetCategory.FANTASY, "dragon", true, Look(
        furLength = 0f, primary = 0x8B5CF6, secondary = 0xFFD27A, accent = 0x5B32C8,
        inner = 0xB794FF, eye = 0xFFB31F, nose = 0x4C2A9E, ear = EarType.HORNS,
        tail = TailType.DRAGON, wings = true, scales = true, eyeScale = 1.08f),
        Personality(walkSpeed = 1f, energy = 1.2f, special = listOf("fly"))),

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

/** Wearable 3D accessories. Prices: 0 = free, >0 coins, <0 gems. */
enum class Slot { HAT, GLASSES, CLOTHES }

enum class Accessory(val slot: Slot, val title: String, val price: Int, val color: Int) {
    PARTY_HAT(Slot.HAT, "Party Hat", 0, 0xF472B6),
    BEANIE(Slot.HAT, "Cozy Beanie", 120, 0x38BDF8),
    CROWN(Slot.HAT, "Royal Crown", -10, 0xFFC83D),
    BOW(Slot.HAT, "Big Bow", 80, 0xF43F5E),
    ROUND_GLASSES(Slot.GLASSES, "Round Glasses", 0, 0x2A2238),
    SUNGLASSES(Slot.GLASSES, "Star Shades", 150, 0x8B5CF6),
    SCARF(Slot.CLOTHES, "Winter Scarf", 100, 0x8B5CF6),
    BANDANA(Slot.CLOTHES, "Bandana", 0, 0xEF4444),
    SWEATER(Slot.CLOTHES, "Comfy Sweater", -12, 0xFFC83D),
}
