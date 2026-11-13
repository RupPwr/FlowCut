package com.flowcut.app.engine

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.flowcut.app.model.Clip
import com.flowcut.app.model.TransitionType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class ClipFilterState(
    val filterPresetId: Int = 0,
    val filterIntensity: Float = 0.8f,
    val effectPresetId: Int = 0,
    val effectIntensity: Float = 0.7f
) {
    companion object {
        val DEFAULT = ClipFilterState()
    }
}

data class DrawSpec(
    var type: TransitionType = TransitionType.NONE,
    var transitionType: Int = -1,
    var easedP: Float = 0f,
    var matA: FloatArray = FloatArray(16),
    var matB: FloatArray = FloatArray(16),
    var cropA: FloatArray = FloatArray(2),
    var cropB: FloatArray = FloatArray(2),
    var opacityA: Float = 1.0f,
    var opacityB: Float = 1.0f,
    var adjA0: FloatArray? = null,
    var adjA1: FloatArray? = null,
    var adjA2: FloatArray? = null,
    var adjB0: FloatArray? = null,
    var adjB1: FloatArray? = null,
    var adjB2: FloatArray? = null,
    var texelSize: FloatArray? = null,
    var fgNormMatA: FloatArray? = null,
    var fgNormMatB: FloatArray? = null,
    var bgMatA: FloatArray? = null,
    var bgMatB: FloatArray? = null,
    var bgModeA: Int = 0,
    var bgModeB: Int = 0,
    var bgParamsA: FloatArray? = null,
    var bgParamsB: FloatArray? = null,
    var filterPresetId: Int = 0,
    var filterIntensity: Float = 0.0f,
    var effectPresetId: Int = 0,
    var effectIntensity: Float = 0.0f,
    var timeSec: Float = 0.0f,
    var aspectRatio: Float = 9.0f / 16.0f,
    var clipBrightness: Float = 0f,
    var clipContrast: Float = 1f,
    var clipSaturation: Float = 1f,
    var clipWarmth: Float = 0f,
    var clipVignette: Float = 0f
)

class TransitionRenderer(private val cacheDir: File? = null) {

    private var vaoId = 0
    private var vboId = 0
    private var texAId = 0
    private var texBId = 0

    // Strict rule: compile at most ONE lazy program per frame call
    @Volatile
    private var lazyCompileBudgetRemaining = 1

    private var glRenderer: String = ""
    private var glVersion: String = ""

    private class BaseUniforms {
        var programId = 0
        var uTexALoc = -1
        var uMatALoc = -1
        var uCropALoc = -1
        var uOpacityALoc = -1
        var uFgNormMatALoc = -1
        var uBgMatALoc = -1
        var uBgModeALoc = -1
        var uBgParamsALoc = -1
        var uTexelSizeLoc = -1
    }

    private class GradeEffectUniforms {
        var programId = 0
        var uTexALoc = -1
        var uMatALoc = -1
        var uCropALoc = -1
        var uOpacityALoc = -1
        var uFgNormMatALoc = -1
        var uBgMatALoc = -1
        var uBgModeALoc = -1
        var uBgParamsALoc = -1
        var uTexelSizeLoc = -1
        var uAdjA0Loc = -1
        var uAdjA1Loc = -1
        var uAdjA2Loc = -1
        var uFilterPresetIdLoc = -1
        var uFilterIntensityLoc = -1
        var uEffectPresetIdLoc = -1
        var uEffectIntensityLoc = -1
        var uTimeSecLoc = -1
    }

    private class TransitionUniforms {
        var programId = 0
        var uTexALoc = -1
        var uTexBLoc = -1
        var uMatALoc = -1
        var uMatBLoc = -1
        var uCropALoc = -1
        var uCropBLoc = -1
        var uPLoc = -1
        var uTypeLoc = -1
        var uAspectLoc = -1
        var uAdjA0Loc = -1
        var uAdjA1Loc = -1
        var uAdjA2Loc = -1
        var uAdjB0Loc = -1
        var uAdjB1Loc = -1
        var uAdjB2Loc = -1
        var uTexelSizeLoc = -1
        var uFgNormMatALoc = -1
        var uFgNormMatBLoc = -1
        var uBgMatALoc = -1
        var uBgMatBLoc = -1
        var uBgModeALoc = -1
        var uBgModeBLoc = -1
        var uBgParamsALoc = -1
        var uBgParamsBLoc = -1
        var uOpacityALoc = -1
        var uOpacityBLoc = -1
        var uFilterPresetIdLoc = -1
        var uFilterIntensityLoc = -1
        var uEffectPresetIdLoc = -1
        var uEffectIntensityLoc = -1
        var uTimeSecLoc = -1
    }

    private val baseUniforms = BaseUniforms()
    private val gradeEffectUniforms = GradeEffectUniforms()
    private val transitionUniforms = TransitionUniforms()

    // Overlay compositor program and uniforms
    private var overlayProgramId = 0
    private var uOverlayMatLoc = -1
    private var uOverlayTexLoc = -1
    private var uOverlayOpacityLoc = -1
    private var uCropBoundsLoc = -1
    private var uMaskTypeLoc = -1
    private var uMaskFeatherLoc = -1
    private var uMaskInvertLoc = -1
    private var uChromaEnabledLoc = -1
    private var uChromaKeyRgbLoc = -1
    private var uChromaThresholdLoc = -1
    private var uChromaBlendLoc = -1
    private var uAdjustBrightnessLoc = -1
    private var uAdjustContrastLoc = -1
    private var uAdjustSaturationLoc = -1
    private val overlayMvp = FloatArray(16)

    // Mosaic Pass Program and Uniforms (Phase 9)
    private var mosaicProgramId = 0
    private var uMosaicMvpLoc = -1
    private var uMosaicVideoTexLoc = -1
    private var uMosaicVideoMatLoc = -1
    private var uMosaicCropLoc = -1
    private var uMosaicStyleLoc = -1
    private var uMosaicIntensityLoc = -1
    private var uMosaicFeatherLoc = -1
    private var uMosaicOpacityLoc = -1
    private var uMosaicTexelSizeLoc = -1
    private val mosaicTexelSize = FloatArray(2)
    private val defaultCrop = floatArrayOf(1f, 1f)

    // Pre-allocated fallback arrays for zero allocations (Rule R10)
    private val defaultAdj = FloatArray(4)
    private val defaultTexelSize = floatArrayOf(1f / 720f, 1f / 1280f)
    private val defaultIdentityMat = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
    private val defaultBgParams = FloatArray(4)

    private val vertexShaderSource = """
        #version 300 es
        layout(location = 0) in vec4 aPos;
        layout(location = 1) in vec2 aUV;
        out vec2 vUV;
        void main() {
            gl_Position = aPos;
            vUV = aUV;
        }
    """.trimIndent()

    // Program 1: Base Passthrough (< 10ms compile on Mali/Adreno).
    // Single external OES texture, ZERO transitions, ZERO filters, ZERO effects, ZERO loops.
    private val baseFragmentShaderSource = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;

        uniform samplerExternalOES uTexA;
        uniform mat4 uMatA;
        uniform vec2 uCropA;
        uniform mat4 uFgNormMatA;
        uniform mat4 uBgMatA;
        uniform int uBgModeA;
        uniform vec4 uBgParamsA;
        uniform float uOpacityA;
        uniform vec2 uTexelSize;

        in vec2 vUV;
        out vec4 outColor;

        vec4 sampleBlur4(samplerExternalOES tex, vec2 uv, float blurLevel, vec2 texelSize) {
            if (blurLevel <= 0.001) {
                return texture(tex, uv) * 0.88;
            }
            float radius = max(1.0, blurLevel * 16.0);
            vec2 stride = texelSize * radius;
            vec4 sum = texture(tex, uv) * 0.28;
            sum += texture(tex, clamp(uv + vec2( stride.x,  stride.y), 0.001, 0.999)) * 0.18;
            sum += texture(tex, clamp(uv + vec2(-stride.x,  stride.y), 0.001, 0.999)) * 0.18;
            sum += texture(tex, clamp(uv + vec2( stride.x, -stride.y), 0.001, 0.999)) * 0.18;
            sum += texture(tex, clamp(uv + vec2(-stride.x, -stride.y), 0.001, 0.999)) * 0.18;
            sum.rgb *= 0.88;
            sum.a = 1.0;
            return sum;
        }

        void main() {
            vec2 cropUv = (vUV - 0.5) * uCropA + 0.5;
            vec2 fgCoord = (uFgNormMatA * vec4(cropUv, 0.0, 1.0)).xy;
            float edgeDist = min(min(fgCoord.x, 1.0 - fgCoord.x), min(fgCoord.y, 1.0 - fgCoord.y));
            float feather = max(fwidth(fgCoord.x), fwidth(fgCoord.y)) * 1.5;
            if (feather < 0.001) feather = 0.001;
            float fgAlpha = smoothstep(0.0, feather, edgeDist);
            fgAlpha *= clamp(uOpacityA, 0.0, 1.0);

            if (fgAlpha >= 0.999) {
                vec2 texUv = (uMatA * vec4(fgCoord, 0.0, 1.0)).xy;
                outColor = texture(uTexA, texUv);
                return;
            }

            vec4 bg = vec4(0.0, 0.0, 0.0, 1.0);
            if (fgAlpha < 0.999) {
                if (uBgModeA == 1) {
                    bg = vec4(uBgParamsA.rgb, 1.0);
                } else if (uBgModeA == 2) {
                    vec2 bgUv = (uBgMatA * vec4(cropUv, 0.0, 1.0)).xy;
                    bg = sampleBlur4(uTexA, bgUv, uBgParamsA.a, uTexelSize);
                } else if (uBgModeA == 3) {
                    int gIdx = int(uBgParamsA.r + 0.5);
                    vec3 cTop = vec3(0.12, 0.05, 0.25);
                    vec3 cBot = vec3(0.55, 0.10, 0.45);
                    if (gIdx == 1) {
                        cTop = vec3(0.05, 0.10, 0.25);
                        cBot = vec3(0.00, 0.50, 0.65);
                    } else if (gIdx == 2) {
                        cTop = vec3(0.65, 0.20, 0.10);
                        cBot = vec3(0.25, 0.05, 0.15);
                    } else if (gIdx == 3) {
                        cTop = vec3(0.05, 0.20, 0.15);
                        cBot = vec3(0.10, 0.45, 0.40);
                    } else if (gIdx == 4) {
                        cTop = vec3(0.10, 0.12, 0.28);
                        cBot = vec3(0.60, 0.25, 0.35);
                    } else if (gIdx == 5) {
                        cTop = vec3(0.20, 0.20, 0.22);
                        cBot = vec3(0.04, 0.04, 0.05);
                    }
                    bg = vec4(mix(cTop, cBot, cropUv.y), 1.0);
                }
            }

            if (fgAlpha <= 0.0) {
                outColor = bg;
                return;
            }

            vec2 texUv = (uMatA * vec4(fgCoord, 0.0, 1.0)).xy;
            vec4 fg = texture(uTexA, texUv);
            outColor = mix(bg, fg, fgAlpha);
        }
    """.trimIndent()

    // Program 2: Color Grade & Effect Variant (Compiled lazily only when color/filter/effect active).
    // Single external OES texture, loop-free.
    private val gradeEffectFragmentShaderSource = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;

        uniform samplerExternalOES uTexA;
        uniform mat4 uMatA;
        uniform vec2 uCropA;
        uniform mat4 uFgNormMatA;
        uniform mat4 uBgMatA;
        uniform int uBgModeA;
        uniform vec4 uBgParamsA;
        uniform float uOpacityA;
        uniform vec2 uTexelSize;

        uniform vec4 uAdjA0;
        uniform vec4 uAdjA1;
        uniform vec4 uAdjA2;

        uniform int uFilterPresetId;
        uniform float uFilterIntensity;
        uniform int uEffectPresetId;
        uniform float uEffectIntensity;
        uniform float uTimeSec;

        in vec2 vUV;
        out vec4 outColor;

        vec4 applyColorAdjust(samplerExternalOES tex, vec2 uv, vec4 adj0, vec4 adj1, vec4 adj2, vec2 texelSize) {
            if (dot(abs(adj0), vec4(1.0)) == 0.0 &&
                dot(abs(adj1), vec4(1.0)) == 0.0 &&
                dot(abs(adj2.xy), vec2(1.0)) == 0.0) {
                return texture(tex, uv);
            }

            float brightness = adj0.x;
            float contrast   = adj0.y;
            float saturation = adj0.z;
            float exposure   = adj0.w;

            float warmth     = adj1.x;
            float tint       = adj1.y;
            float highlights = adj1.z;
            float shadows    = adj1.w;

            float sharpness  = adj2.x;
            float vignette   = adj2.y;

            vec4 color = texture(tex, uv);

            if (sharpness > 0.001) {
                vec3 nLeft  = texture(tex, uv - vec2(texelSize.x, 0.0)).rgb;
                vec3 nRight = texture(tex, uv + vec2(texelSize.x, 0.0)).rgb;
                vec3 nDown  = texture(tex, uv - vec2(0.0, texelSize.y)).rgb;
                vec3 nUp    = texture(tex, uv + vec2(0.0, texelSize.y)).rgb;
                vec3 neighbors = nLeft + nRight + nDown + nUp;
                color.rgb += (color.rgb * 4.0 - neighbors) * (sharpness * 1.2);
            }

            color.rgb *= exp2(exposure * 1.2);
            color.rgb += brightness * 0.35;

            float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            float shadowWeight = (1.0 - clamp(luma, 0.0, 1.0)) * (1.0 - clamp(luma, 0.0, 1.0));
            float highlightWeight = clamp(luma, 0.0, 1.0) * clamp(luma, 0.0, 1.0);
            color.rgb += (shadows * shadowWeight + highlights * highlightWeight) * 0.35;

            color.rgb = (color.rgb - 0.5) * (1.0 + contrast * 0.85) + 0.5;

            float lumaAfter = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            color.rgb = mix(vec3(lumaAfter), color.rgb, max(0.0, 1.0 + saturation));

            color.r += warmth * 0.12;
            color.b -= warmth * 0.12;
            color.g -= tint * 0.10;
            color.r += tint * 0.05;
            color.b += tint * 0.05;

            float dist = length(uv - vec2(0.5, 0.5));
            float vigFactor = smoothstep(0.25, 0.85, dist);
            if (vignette > 0.0) {
                color.rgb *= max(0.0, 1.0 - vignette * vigFactor);
            } else if (vignette < 0.0) {
                color.rgb *= (1.0 + vignette * vigFactor);
            }

            color.rgb = clamp(color.rgb, 0.0, 1.0);
            return color;
        }

        vec4 sampleBlur(samplerExternalOES tex, vec2 uv, float blurLevel, vec2 texelSize) {
            if (blurLevel <= 0.001) {
                return texture(tex, uv) * 0.88;
            }
            float radius = max(1.0, blurLevel * 20.0);
            vec2 stride = (texelSize * 4.0) * radius;
            vec4 sum = texture(tex, uv) * 0.20;
            vec2 r1 = stride * 0.5;
            sum += texture(tex, clamp(uv + vec2( r1.x,  0.0 ), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2(-r1.x,  0.0 ), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2( 0.0,   r1.y), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2( 0.0,  -r1.y), 0.001, 0.999)) * 0.11;
            vec2 r2 = stride * 0.7071;
            sum += texture(tex, clamp(uv + vec2( r2.x,  r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2(-r2.x,  r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2( r2.x, -r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2(-r2.x, -r2.y), 0.001, 0.999)) * 0.09;
            sum.rgb *= 0.88;
            sum.a = 1.0;
            return sum;
        }

        vec4 sampleClip(vec2 uv) {
            vec2 cropUv = (uv - 0.5) * uCropA + 0.5;
            vec2 fgCoord = (uFgNormMatA * vec4(cropUv, 0.0, 1.0)).xy;
            float edgeDist = min(min(fgCoord.x, 1.0 - fgCoord.x), min(fgCoord.y, 1.0 - fgCoord.y));
            float feather = max(fwidth(fgCoord.x), fwidth(fgCoord.y)) * 1.5;
            if (feather < 0.001) feather = 0.001;
            float fgAlpha = smoothstep(0.0, feather, edgeDist);
            fgAlpha *= clamp(uOpacityA, 0.0, 1.0);

            if (fgAlpha >= 0.999) {
                vec2 texUv = (uMatA * vec4(fgCoord, 0.0, 1.0)).xy;
                return applyColorAdjust(uTexA, texUv, uAdjA0, uAdjA1, uAdjA2, uTexelSize);
            }

            vec4 bg = vec4(0.0, 0.0, 0.0, 1.0);
            if (fgAlpha < 0.999) {
                if (uBgModeA == 1) {
                    bg = vec4(uBgParamsA.rgb, 1.0);
                } else if (uBgModeA == 2) {
                    vec2 bgUv = (uBgMatA * vec4(cropUv, 0.0, 1.0)).xy;
                    bg = sampleBlur(uTexA, bgUv, uBgParamsA.a, uTexelSize);
                } else if (uBgModeA == 3) {
                    int gIdx = int(uBgParamsA.r + 0.5);
                    vec3 cTop = vec3(0.12, 0.05, 0.25);
                    vec3 cBot = vec3(0.55, 0.10, 0.45);
                    if (gIdx == 1) {
                        cTop = vec3(0.05, 0.10, 0.25);
                        cBot = vec3(0.00, 0.50, 0.65);
                    } else if (gIdx == 2) {
                        cTop = vec3(0.65, 0.20, 0.10);
                        cBot = vec3(0.25, 0.05, 0.15);
                    } else if (gIdx == 3) {
                        cTop = vec3(0.05, 0.20, 0.15);
                        cBot = vec3(0.10, 0.45, 0.40);
                    } else if (gIdx == 4) {
                        cTop = vec3(0.10, 0.12, 0.28);
                        cBot = vec3(0.60, 0.25, 0.35);
                    } else if (gIdx == 5) {
                        cTop = vec3(0.20, 0.20, 0.22);
                        cBot = vec3(0.04, 0.04, 0.05);
                    }
                    bg = vec4(mix(cTop, cBot, cropUv.y), 1.0);
                }
            }

            if (fgAlpha <= 0.0) {
                return bg;
            }

            vec2 texUv = (uMatA * vec4(fgCoord, 0.0, 1.0)).xy;
            vec4 fg = applyColorAdjust(uTexA, texUv, uAdjA0, uAdjA1, uAdjA2, uTexelSize);
            return mix(bg, fg, fgAlpha);
        }

        vec2 applyUvEffects(vec2 uv, int effectId, float intensity, float timeSec) {
            if (effectId == 0 || intensity <= 0.001) return uv;
            vec2 effUv = uv;
            if (effectId == 4) {
                float cycle = mod(timeSec, 0.85);
                float p = sin(cycle * 3.14159265 / 0.85);
                float pulse = pow(max(0.0, p), 4.0) * (0.08 * clamp(intensity, 0.0, 1.0));
                effUv = (effUv - 0.5) / (1.0 + pulse) + 0.5;
            } else if (effectId == 5) {
                float sX = sin(timeSec * 11.0) * 0.6 + sin(timeSec * 23.0) * 0.4;
                float sY = cos(timeSec * 13.0) * 0.6 + cos(timeSec * 29.0) * 0.4;
                vec2 shake = vec2(sX, sY) * (0.02 * clamp(intensity, 0.0, 1.0));
                effUv += shake;
            } else if (effectId == 8) {
                float minGrid = 20.0;
                float maxGrid = 180.0;
                float grid = mix(maxGrid, minGrid, clamp(intensity, 0.0, 1.0));
                effUv = floor(effUv * grid) / grid;
            }
            return effUv;
        }

        vec3 applyFilter(vec3 inRgb, int presetId, float intensity) {
            if (presetId == 0 || intensity <= 0.001) return inRgb;
            float luma = dot(inRgb, vec3(0.2126, 0.7152, 0.0722));
            vec3 filtered = inRgb;

            if (presetId == 1) {
                vec3 sat = mix(vec3(luma), inRgb, 1.45);
                filtered = sat * sat * (3.0 - 2.0 * sat);
            } else if (presetId == 2) {
                filtered = inRgb * vec3(1.16, 1.05, 0.88) + vec3(0.06, 0.03, 0.0);
            } else if (presetId == 3) {
                vec3 coolTint = inRgb * vec3(0.88, 1.02, 1.20);
                filtered = coolTint + vec3(0.0, 0.02, 0.08) * (1.0 - luma);
            } else if (presetId == 4) {
                vec3 shadowTeal = vec3(-0.05, 0.08, 0.16) * (1.0 - luma);
                vec3 highlightOrange = vec3(0.18, 0.08, -0.06) * luma;
                filtered = inRgb + shadowTeal + highlightOrange;
                filtered = (filtered - 0.5) * 1.15 + 0.5;
            } else if (presetId == 5) {
                vec3 lifted = inRgb * 0.88 + vec3(0.08, 0.06, 0.04);
                vec3 sepia = vec3(luma * 1.15, luma * 0.95, luma * 0.78);
                filtered = mix(lifted, sepia, 0.55);
            } else if (presetId == 6) {
                float noirVal = smoothstep(0.10, 0.90, luma);
                filtered = vec3(noirVal);
            } else if (presetId == 7) {
                vec3 lifted = inRgb * 0.80 + 0.14;
                float lLuma = dot(lifted, vec3(0.2126, 0.7152, 0.0722));
                filtered = mix(vec3(lLuma), lifted, 0.70);
            } else if (presetId == 8) {
                vec3 neon;
                neon.r = inRgb.r * 1.30 + 0.12 * (1.0 - inRgb.g);
                neon.g = inRgb.g * 0.75;
                neon.b = inRgb.b * 1.40 + 0.15 * (1.0 - inRgb.r);
                filtered = mix(inRgb, neon, 0.85);
            } else if (presetId == 9) {
                vec3 amber = vec3(1.25, 1.04, 0.80);
                filtered = pow(clamp(inRgb * amber, 0.0, 1.0), vec3(0.90)) + vec3(0.07, 0.04, 0.0) * luma;
            } else if (presetId == 10) {
                vec3 crushed = max(vec3(0.0), (inRgb - 0.08) / 0.92);
                float cLuma = dot(crushed, vec3(0.2126, 0.7152, 0.0722));
                vec3 desat = mix(vec3(cLuma), crushed, 0.55);
                filtered = pow(clamp(desat, 0.0, 1.0), vec3(1.25));
            } else if (presetId == 11) {
                vec3 soft = (inRgb - 0.5) * 0.72 + 0.55;
                filtered = soft * vec3(1.06, 1.03, 1.09) + vec3(0.05, 0.05, 0.06);
            }

            filtered = clamp(filtered, 0.0, 1.0);
            return mix(inRgb, filtered, clamp(intensity, 0.0, 1.0));
        }

        void main() {
            vec2 effUv = applyUvEffects(vUV, uEffectPresetId, uEffectIntensity, uTimeSec);
            vec4 c;

            if (uEffectPresetId == 1 && uEffectIntensity > 0.001) {
                float splitOffset = 0.025 * clamp(uEffectIntensity, 0.0, 1.0);
                float r = sampleClip(vec2(effUv.x + splitOffset, effUv.y)).r;
                vec4 centerCol = sampleClip(effUv);
                float b = sampleClip(vec2(effUv.x - splitOffset, effUv.y)).b;
                c = vec4(r, centerCol.g, b, centerCol.a);
            } else {
                c = sampleClip(effUv);
            }

            if (uEffectPresetId > 0 && uEffectIntensity > 0.001) {
                if (uEffectPresetId == 2) {
                    float dist = length(effUv - vec2(0.5, 0.5));
                    float vig = smoothstep(0.25, 0.82, dist);
                    c.rgb *= mix(1.0, 1.0 - 0.80 * vig, clamp(uEffectIntensity, 0.0, 1.0));
                } else if (uEffectPresetId == 3) {
                    float scan = sin((effUv.y + uTimeSec * 0.05) * 650.0) * 0.5 + 0.5;
                    float lineFactor = clamp(scan * 0.30 + 0.80, 0.0, 1.0);
                    vec3 vhsCol = c.rgb * lineFactor * vec3(1.06, 1.0, 0.90);
                    c.rgb = mix(c.rgb, vhsCol, clamp(uEffectIntensity, 0.0, 1.0));
                } else if (uEffectPresetId == 6) {
                    float cycle = mod(uTimeSec, 0.50);
                    float f = sin(cycle * 3.14159265 / 0.50);
                    float flash = pow(max(0.0, f), 6.0) * clamp(uEffectIntensity, 0.0, 1.0);
                    c.rgb = mix(c.rgb, vec3(1.0), flash * 0.85);
                } else if (uEffectPresetId == 7) {
                    float glowLuma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
                    float glowWeight = smoothstep(0.35, 0.85, glowLuma);
                    vec3 glowCol = c.rgb + vec3(0.28, 0.22, 0.35) * glowWeight;
                    c.rgb = mix(c.rgb, glowCol, clamp(uEffectIntensity, 0.0, 1.0));
                }
            }

            c.rgb = applyFilter(c.rgb, uFilterPresetId, uFilterIntensity);
            outColor = vec4(c.rgb, 1.0);
        }
    """.trimIndent()

    // Program 3: Dual-Texture Transition Program (Compiled lazily only during transitions).
    private val transitionFragmentShaderSource = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;

        uniform samplerExternalOES uTexA;
        uniform samplerExternalOES uTexB;
        uniform mat4 uMatA;
        uniform mat4 uMatB;
        uniform vec2 uCropA;
        uniform vec2 uCropB;
        uniform float uP;
        uniform int uType;
        uniform float uAspect;

        uniform vec4 uAdjA0;
        uniform vec4 uAdjA1;
        uniform vec4 uAdjA2;
        uniform vec4 uAdjB0;
        uniform vec4 uAdjB1;
        uniform vec4 uAdjB2;
        uniform vec2 uTexelSize;

        uniform mat4 uFgNormMatA;
        uniform mat4 uFgNormMatB;
        uniform mat4 uBgMatA;
        uniform mat4 uBgMatB;
        uniform int uBgModeA;
        uniform int uBgModeB;
        uniform vec4 uBgParamsA;
        uniform vec4 uBgParamsB;
        uniform float uOpacityA;
        uniform float uOpacityB;

        in vec2 vUV;
        out vec4 outColor;

        vec4 applyColorAdjust(samplerExternalOES tex, vec2 uv, vec4 adj0, vec4 adj1, vec4 adj2, vec2 texelSize) {
            if (dot(abs(adj0), vec4(1.0)) == 0.0 &&
                dot(abs(adj1), vec4(1.0)) == 0.0 &&
                dot(abs(adj2.xy), vec2(1.0)) == 0.0) {
                return texture(tex, uv);
            }

            float brightness = adj0.x;
            float contrast   = adj0.y;
            float saturation = adj0.z;
            float exposure   = adj0.w;

            float warmth     = adj1.x;
            float tint       = adj1.y;
            float highlights = adj1.z;
            float shadows    = adj1.w;

            float sharpness  = adj2.x;
            float vignette   = adj2.y;

            vec4 color = texture(tex, uv);

            if (sharpness > 0.001) {
                vec3 nLeft  = texture(tex, uv - vec2(texelSize.x, 0.0)).rgb;
                vec3 nRight = texture(tex, uv + vec2(texelSize.x, 0.0)).rgb;
                vec3 nDown  = texture(tex, uv - vec2(0.0, texelSize.y)).rgb;
                vec3 nUp    = texture(tex, uv + vec2(0.0, texelSize.y)).rgb;
                vec3 neighbors = nLeft + nRight + nDown + nUp;
                color.rgb += (color.rgb * 4.0 - neighbors) * (sharpness * 1.2);
            }

            color.rgb *= exp2(exposure * 1.2);
            color.rgb += brightness * 0.35;

            float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            float shadowWeight = (1.0 - clamp(luma, 0.0, 1.0)) * (1.0 - clamp(luma, 0.0, 1.0));
            float highlightWeight = clamp(luma, 0.0, 1.0) * clamp(luma, 0.0, 1.0);
            color.rgb += (shadows * shadowWeight + highlights * highlightWeight) * 0.35;

            color.rgb = (color.rgb - 0.5) * (1.0 + contrast * 0.85) + 0.5;

            float lumaAfter = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            color.rgb = mix(vec3(lumaAfter), color.rgb, max(0.0, 1.0 + saturation));

            color.r += warmth * 0.12;
            color.b -= warmth * 0.12;
            color.g -= tint * 0.10;
            color.r += tint * 0.05;
            color.b += tint * 0.05;

            float dist = length(uv - vec2(0.5, 0.5));
            float vigFactor = smoothstep(0.25, 0.85, dist);
            if (vignette > 0.0) {
                color.rgb *= max(0.0, 1.0 - vignette * vigFactor);
            } else if (vignette < 0.0) {
                color.rgb *= (1.0 + vignette * vigFactor);
            }

            color.rgb = clamp(color.rgb, 0.0, 1.0);
            return color;
        }

        vec4 sampleBlur(samplerExternalOES tex, vec2 uv, float blurLevel, vec2 texelSize) {
            if (blurLevel <= 0.001) {
                return texture(tex, uv) * 0.88;
            }
            float radius = max(1.0, blurLevel * 20.0);
            vec2 stride = (texelSize * 4.0) * radius;
            vec4 sum = texture(tex, uv) * 0.20;
            vec2 r1 = stride * 0.5;
            sum += texture(tex, clamp(uv + vec2( r1.x,  0.0 ), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2(-r1.x,  0.0 ), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2( 0.0,   r1.y), 0.001, 0.999)) * 0.11;
            sum += texture(tex, clamp(uv + vec2( 0.0,  -r1.y), 0.001, 0.999)) * 0.11;
            vec2 r2 = stride * 0.7071;
            sum += texture(tex, clamp(uv + vec2( r2.x,  r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2(-r2.x,  r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2( r2.x, -r2.y), 0.001, 0.999)) * 0.09;
            sum += texture(tex, clamp(uv + vec2(-r2.x, -r2.y), 0.001, 0.999)) * 0.09;
            sum.rgb *= 0.88;
            sum.a = 1.0;
            return sum;
        }

        vec4 sampleClipA(vec2 uv) {
            vec2 cropUv = (uv - 0.5) * uCropA + 0.5;
            vec2 fgCoord = (uFgNormMatA * vec4(cropUv, 0.0, 1.0)).xy;
            float edgeDist = min(min(fgCoord.x, 1.0 - fgCoord.x), min(fgCoord.y, 1.0 - fgCoord.y));
            float feather = max(fwidth(fgCoord.x), fwidth(fgCoord.y)) * 1.5;
            if (feather < 0.001) feather = 0.001;
            float fgAlpha = smoothstep(0.0, feather, edgeDist);
            fgAlpha *= clamp(uOpacityA, 0.0, 1.0);

            if (fgAlpha >= 0.999) {
                vec2 texUv = (uMatA * vec4(fgCoord, 0.0, 1.0)).xy;
                return applyColorAdjust(uTexA, texUv, uAdjA0, uAdjA1, uAdjA2, uTexelSize);
            }

            vec4 bg = vec4(0.0, 0.0, 0.0, 1.0);
            if (fgAlpha < 0.999) {
                if (uBgModeA == 1) {
                    bg = vec4(uBgParamsA.rgb, 1.0);
                } else if (uBgModeA == 2) {
                    vec2 bgUv = (uBgMatA * vec4(cropUv, 0.0, 1.0)).xy;
                    bg = sampleBlur(uTexA, bgUv, uBgParamsA.a, uTexelSize);
                } else if (uBgModeA == 3) {
                    int gIdx = int(uBgParamsA.r + 0.5);
                    vec3 cTop = vec3(0.12, 0.05, 0.25);
                    vec3 cBot = vec3(0.55, 0.10, 0.45);
                    if (gIdx == 1) {
                        cTop = vec3(0.05, 0.10, 0.25);
                        cBot = vec3(0.00, 0.50, 0.65);
                    } else if (gIdx == 2) {
                        cTop = vec3(0.65, 0.20, 0.10);
                        cBot = vec3(0.25, 0.05, 0.15);
                    } else if (gIdx == 3) {
                        cTop = vec3(0.05, 0.20, 0.15);
                        cBot = vec3(0.10, 0.45, 0.40);
                    } else if (gIdx == 4) {
                        cTop = vec3(0.10, 0.12, 0.28);
                        cBot = vec3(0.60, 0.25, 0.35);
                    } else if (gIdx == 5) {
                        cTop = vec3(0.20, 0.20, 0.22);
                        cBot = vec3(0.04, 0.04, 0.05);
                    }
                    bg = vec4(mix(cTop, cBot, cropUv.y), 1.0);
                }
            }

            if (fgAlpha <= 0.0) return bg;

            vec2 texUv = (uMatA * vec4(fgCoord, 0.0, 1.0)).xy;
            vec4 fg = applyColorAdjust(uTexA, texUv, uAdjA0, uAdjA1, uAdjA2, uTexelSize);
            return mix(bg, fg, fgAlpha);
        }

        vec4 sampleClipB(vec2 uv) {
            vec2 cropUv = (uv - 0.5) * uCropB + 0.5;
            vec2 fgCoord = (uFgNormMatB * vec4(cropUv, 0.0, 1.0)).xy;
            float edgeDist = min(min(fgCoord.x, 1.0 - fgCoord.x), min(fgCoord.y, 1.0 - fgCoord.y));
            float feather = max(fwidth(fgCoord.x), fwidth(fgCoord.y)) * 1.5;
            if (feather < 0.001) feather = 0.001;
            float fgAlpha = smoothstep(0.0, feather, edgeDist);
            fgAlpha *= clamp(uOpacityB, 0.0, 1.0);

            if (fgAlpha >= 0.999) {
                vec2 texUv = (uMatB * vec4(fgCoord, 0.0, 1.0)).xy;
                return applyColorAdjust(uTexB, texUv, uAdjB0, uAdjB1, uAdjB2, uTexelSize);
            }

            vec4 bg = vec4(0.0, 0.0, 0.0, 1.0);
            if (fgAlpha < 0.999) {
                if (uBgModeB == 1) {
                    bg = vec4(uBgParamsB.rgb, 1.0);
                } else if (uBgModeB == 2) {
                    vec2 bgUv = (uBgMatB * vec4(cropUv, 0.0, 1.0)).xy;
                    bg = sampleBlur(uTexB, bgUv, uBgParamsB.a, uTexelSize);
                } else if (uBgModeB == 3) {
                    int gIdx = int(uBgParamsB.r + 0.5);
                    vec3 cTop = vec3(0.12, 0.05, 0.25);
                    vec3 cBot = vec3(0.55, 0.10, 0.45);
                    if (gIdx == 1) {
                        cTop = vec3(0.05, 0.10, 0.25);
                        cBot = vec3(0.00, 0.50, 0.65);
                    } else if (gIdx == 2) {
                        cTop = vec3(0.65, 0.20, 0.10);
                        cBot = vec3(0.25, 0.05, 0.15);
                    } else if (gIdx == 3) {
                        cTop = vec3(0.05, 0.20, 0.15);
                        cBot = vec3(0.10, 0.45, 0.40);
                    } else if (gIdx == 4) {
                        cTop = vec3(0.10, 0.12, 0.28);
                        cBot = vec3(0.60, 0.25, 0.35);
                    } else if (gIdx == 5) {
                        cTop = vec3(0.20, 0.20, 0.22);
                        cBot = vec3(0.04, 0.04, 0.05);
                    }
                    bg = vec4(mix(cTop, cBot, cropUv.y), 1.0);
                }
            }

            if (fgAlpha <= 0.0) return bg;

            vec2 texUv = (uMatB * vec4(fgCoord, 0.0, 1.0)).xy;
            vec4 fg = applyColorAdjust(uTexB, texUv, uAdjB0, uAdjB1, uAdjB2, uTexelSize);
            return mix(bg, fg, fgAlpha);
        }

        vec4 sA(vec2 uv) { return sampleClipA(uv); }
        vec4 sB(vec2 uv) { return sampleClipB(uv); }

        vec4 sampleScene(vec2 uv) {
            if (uType == 0) {
                return sA(uv);
            } else if (uType == 1) {
                return mix(sA(uv), sB(uv), uP);
            } else if (uType == 2) {
                if (uP < 0.5) {
                    float f = 1.0 - (uP * 2.0);
                    return sA(uv) * f;
                } else {
                    float f = (uP - 0.5) * 2.0;
                    return sB(uv) * f;
                }
            } else if (uType == 3) {
                if (uP < 0.5) {
                    float f = uP * 2.0;
                    return mix(sA(uv), vec4(1.0, 1.0, 1.0, 1.0), f);
                } else {
                    float f = (1.0 - uP) * 2.0;
                    return mix(sB(uv), vec4(1.0, 1.0, 1.0, 1.0), f);
                }
            } else if (uType == 4) {
                if (uv.x > 1.0 - uP) {
                    return sB(vec2(uv.x - (1.0 - uP), uv.y));
                } else {
                    return sA(vec2(uv.x + uP, uv.y));
                }
            } else if (uType == 5) {
                if (uv.x < uP) {
                    return sB(vec2(uv.x + (1.0 - uP), uv.y));
                } else {
                    return sA(vec2(uv.x - uP, uv.y));
                }
            } else if (uType == 6) {
                float sa = 1.0 + 0.8 * uP;
                vec4 a = sA((uv - 0.5) / sa + 0.5);
                vec4 b = sB(uv);
                return mix(a, b, uP);
            } else if (uType == 7) {
                float sa = max(0.1, 1.0 - 0.6 * uP);
                vec4 a = sA((uv - 0.5) / sa + 0.5);
                vec4 b = sB(uv);
                return mix(a, b, uP);
            } else if (uType == 8) {
                float feather = 0.05;
                float progress = uP * (1.0 + feather);
                float m = smoothstep(progress - feather, progress, uv.y);
                return mix(sB(uv), sA(uv), m);
            } else {
                return mix(sA(uv), sB(uv), uP);
            }
        }

        void main() {
            outColor = vec4(sampleScene(vUV).rgb, 1.0);
        }
    """.trimIndent()

    private val overlayVertexShaderSource = """
        #version 300 es
        layout(location = 0) in vec4 aPos;
        layout(location = 1) in vec2 aUV;
        uniform mat4 uOverlayMat;
        uniform vec4 uCropBounds;
        out vec2 vTexUV;
        out vec2 vLocalUV;
        void main() {
            gl_Position = uOverlayMat * aPos;
            vLocalUV = aUV;
            vTexUV = vec2(
                mix(uCropBounds.x, uCropBounds.z, aUV.x),
                mix(uCropBounds.y, uCropBounds.w, 1.0 - aUV.y)
            );
        }
    """.trimIndent()

    private val overlayFragmentShaderSource = """
        #version 300 es
        precision highp float;
        uniform sampler2D uOverlayTex;
        uniform float uOverlayOpacity;

        uniform int uChromaEnabled;
        uniform vec3 uChromaKeyRgb;
        uniform float uChromaThreshold;
        uniform float uChromaBlend;

        uniform int uMaskType;
        uniform float uMaskFeather;
        uniform int uMaskInvert;

        uniform float uAdjustBrightness;
        uniform float uAdjustContrast;
        uniform float uAdjustSaturation;

        in vec2 vTexUV;
        in vec2 vLocalUV;
        out vec4 outColor;

        float sdHeart(vec2 p) {
            p.y = -p.y + 0.25;
            p.x = abs(p.x) * 1.15;
            if (p.y + p.x > 1.0) {
                return sqrt(dot(p - vec2(0.25, 0.75), p - vec2(0.25, 0.75))) - 0.35355;
            }
            return sqrt(min(dot(p - vec2(0.0, 1.0), p - vec2(0.0, 1.0)),
                            dot(p - 0.5 * max(p.x + p.y, 0.0), p - 0.5 * max(p.x + p.y, 0.0)))) * sign(p.x - p.y);
        }

        float sdStar5(vec2 p, float r, float rf) {
            const vec2 k1 = vec2(0.809016994375, -0.587785252292);
            const vec2 k2 = vec2(-k1.x, k1.y);
            p.x = abs(p.x);
            p -= 2.0 * max(dot(k1, p), 0.0) * k1;
            p -= 2.0 * max(dot(k2, p), 0.0) * k2;
            p.x = abs(p.x);
            p.y -= r;
            vec2 ba = rf * vec2(-k1.y, k1.x) - vec2(0.0, 1.0);
            float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);
            return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y);
        }

        void main() {
            vec4 texColor = texture(uOverlayTex, vTexUV);

            float chromaAlpha = 1.0;
            if (uChromaEnabled == 1) {
                vec3 diff = texColor.rgb - uChromaKeyRgb;
                float colorDist = sqrt(dot(diff * vec3(0.299, 0.587, 0.114), diff));
                float blend = max(uChromaBlend, 0.005);
                chromaAlpha = smoothstep(uChromaThreshold, uChromaThreshold + blend, colorDist);
            }

            float maskAlpha = 1.0;
            if (uMaskType > 0) {
                vec2 p = (vLocalUV - 0.5) * 2.0;
                float d = 0.0;
                if (uMaskType == 1) {
                    d = length(p) - 0.85;
                } else if (uMaskType == 2) {
                    vec2 q = abs(p) - vec2(0.70);
                    d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - 0.15;
                } else if (uMaskType == 3) {
                    d = sdHeart(p);
                } else if (uMaskType == 4) {
                    d = sdStar5(p, 0.85, 0.45);
                } else if (uMaskType == 5) {
                    d = p.y;
                } else if (uMaskType == 6) {
                    d = abs(p.y) - 0.45;
                }
                float feather = max(uMaskFeather, 0.001);
                maskAlpha = 1.0 - smoothstep(-feather, feather + 0.005, d);
                if (uMaskInvert == 1) {
                    maskAlpha = 1.0 - maskAlpha;
                }
            }

            float finalAlpha = texColor.a * clamp(uOverlayOpacity, 0.0, 1.0) * chromaAlpha * maskAlpha;

            vec3 rgb = texColor.rgb;
            rgb += uAdjustBrightness;
            rgb = (rgb - 0.5) * uAdjustContrast + 0.5;
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            rgb = mix(vec3(luma), rgb, uAdjustSaturation);
            rgb = clamp(rgb, 0.0, 1.0);

            outColor = vec4(rgb, finalAlpha);
        }
    """.trimIndent()

    private val mosaicVertexShaderSource = """
        #version 300 es
        layout(location = 0) in vec4 aPos;
        layout(location = 1) in vec2 aUV;
        uniform mat4 uMosaicMvp;
        out vec2 vLocalUV;
        out vec2 vScreenUV;
        void main() {
            gl_Position = uMosaicMvp * aPos;
            vLocalUV = aUV;
            vScreenUV = (gl_Position.xy / gl_Position.w) * 0.5 + 0.5;
        }
    """.trimIndent()

    private val mosaicFragmentShaderSource = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;

        uniform samplerExternalOES uVideoTex;
        uniform mat4 uVideoMat;
        uniform vec2 uCrop;
        uniform int uMosaicStyle;
        uniform float uMosaicIntensity;
        uniform float uMosaicFeather;
        uniform float uOpacity;
        uniform vec2 uTexelSize;

        in vec2 vLocalUV;
        in vec2 vScreenUV;
        out vec4 outColor;

        vec4 sampleVid(vec2 suv) {
            vec2 cropUv = (suv - 0.5) * uCrop + 0.5;
            vec2 vidUv = (uVideoMat * vec4(cropUv, 0.0, 1.0)).xy;
            return texture(uVideoTex, clamp(vidUv, 0.001, 0.999));
        }

        void main() {
            float edgeDist = min(min(vLocalUV.x, 1.0 - vLocalUV.x), min(vLocalUV.y, 1.0 - vLocalUV.y));
            float feather = max(0.001, uMosaicFeather);
            float edgeAlpha = smoothstep(0.0, feather, edgeDist);
            if (edgeAlpha <= 0.001) {
                discard;
            }

            vec4 finalColor = vec4(0.0, 0.0, 0.0, 1.0);

            if (uMosaicStyle == 0) {
                float blockSpan = max(0.005, uMosaicIntensity * 0.06);
                vec2 blockUv = floor(vScreenUV / blockSpan) * blockSpan + blockSpan * 0.5;
                finalColor = sampleVid(blockUv);
            } else if (uMosaicStyle == 1) {
                float radius = max(1.0, uMosaicIntensity * 16.0);
                vec2 stride = uTexelSize * radius;
                vec4 sum = sampleVid(vScreenUV) * 0.24;
                sum += sampleVid(vScreenUV + vec2( stride.x,  0.0)) * 0.12;
                sum += sampleVid(vScreenUV + vec2(-stride.x,  0.0)) * 0.12;
                sum += sampleVid(vScreenUV + vec2( 0.0,  stride.y)) * 0.12;
                sum += sampleVid(vScreenUV + vec2( 0.0, -stride.y)) * 0.12;
                vec2 r2 = stride * 0.7071;
                sum += sampleVid(vScreenUV + vec2( r2.x,  r2.y)) * 0.10;
                sum += sampleVid(vScreenUV + vec2(-r2.x,  r2.y)) * 0.10;
                sum += sampleVid(vScreenUV + vec2( r2.x, -r2.y)) * 0.10;
                sum += sampleVid(vScreenUV + vec2(-r2.x, -r2.y)) * 0.10;
                finalColor = sum;
            } else if (uMosaicStyle == 2) {
                float dotSpacing = max(0.008, uMosaicIntensity * 0.045);
                vec2 cell = fract(vScreenUV / dotSpacing) - 0.5;
                float dist = length(cell);
                vec2 centerUv = floor(vScreenUV / dotSpacing) * dotSpacing + dotSpacing * 0.5;
                vec4 centerCol = sampleVid(centerUv);
                float luma = dot(centerCol.rgb, vec3(0.299, 0.587, 0.114));
                float dotRadius = 0.46 * (1.0 - luma * 0.5);
                float dotMask = smoothstep(dotRadius, dotRadius - 0.06, dist);
                vec3 dotColor = mix(vec3(0.06, 0.06, 0.08), centerCol.rgb * 1.25, dotMask);
                finalColor = vec4(dotColor, 1.0);
            } else if (uMosaicStyle == 3) {
                finalColor = vec4(0.04, 0.04, 0.05, 1.0);
            }

            outColor = vec4(finalColor.rgb, finalColor.a * edgeAlpha * uOpacity);
        }
    """.trimIndent()

    private val quadVertices = floatArrayOf(
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f
    )

    fun init() {
        // Base passthrough program: compiled immediately in < 10ms
        baseUniforms.programId = buildProgram("BasePassthrough", vertexShaderSource, baseFragmentShaderSource)
        initBaseUniforms()

        // Setup VAO & VBO
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)

        val buffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        buffer.position(0)

        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            quadVertices.size * 4,
            buffer,
            GLES30.GL_STATIC_DRAW
        )

        // aPos: location 0, 2 floats, stride = 16
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)

        // aUV: location 1, 2 floats, stride = 16, offset = 8
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // Generate 2 external textures
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        texAId = textures[0]
        texBId = textures[1]

        setupExternalTexture(texAId)
        setupExternalTexture(texBId)
    }

    private fun initBaseUniforms() {
        val p = baseUniforms.programId
        baseUniforms.uTexALoc = GLES30.glGetUniformLocation(p, "uTexA")
        baseUniforms.uMatALoc = GLES30.glGetUniformLocation(p, "uMatA")
        baseUniforms.uCropALoc = GLES30.glGetUniformLocation(p, "uCropA")
        baseUniforms.uOpacityALoc = GLES30.glGetUniformLocation(p, "uOpacityA")
        baseUniforms.uFgNormMatALoc = GLES30.glGetUniformLocation(p, "uFgNormMatA")
        baseUniforms.uBgMatALoc = GLES30.glGetUniformLocation(p, "uBgMatA")
        baseUniforms.uBgModeALoc = GLES30.glGetUniformLocation(p, "uBgModeA")
        baseUniforms.uBgParamsALoc = GLES30.glGetUniformLocation(p, "uBgParamsA")
        baseUniforms.uTexelSizeLoc = GLES30.glGetUniformLocation(p, "uTexelSize")
    }

    private fun getOrCompileGradeEffectProgram(): Int {
        if (gradeEffectUniforms.programId != 0) return gradeEffectUniforms.programId
        if (lazyCompileBudgetRemaining <= 0) return baseUniforms.programId
        lazyCompileBudgetRemaining--
        gradeEffectUniforms.programId = buildProgram("GradeEffect", vertexShaderSource, gradeEffectFragmentShaderSource)
        val p = gradeEffectUniforms.programId
        gradeEffectUniforms.uTexALoc = GLES30.glGetUniformLocation(p, "uTexA")
        gradeEffectUniforms.uMatALoc = GLES30.glGetUniformLocation(p, "uMatA")
        gradeEffectUniforms.uCropALoc = GLES30.glGetUniformLocation(p, "uCropA")
        gradeEffectUniforms.uOpacityALoc = GLES30.glGetUniformLocation(p, "uOpacityA")
        gradeEffectUniforms.uFgNormMatALoc = GLES30.glGetUniformLocation(p, "uFgNormMatA")
        gradeEffectUniforms.uBgMatALoc = GLES30.glGetUniformLocation(p, "uBgMatA")
        gradeEffectUniforms.uBgModeALoc = GLES30.glGetUniformLocation(p, "uBgModeA")
        gradeEffectUniforms.uBgParamsALoc = GLES30.glGetUniformLocation(p, "uBgParamsA")
        gradeEffectUniforms.uTexelSizeLoc = GLES30.glGetUniformLocation(p, "uTexelSize")
        gradeEffectUniforms.uAdjA0Loc = GLES30.glGetUniformLocation(p, "uAdjA0")
        gradeEffectUniforms.uAdjA1Loc = GLES30.glGetUniformLocation(p, "uAdjA1")
        gradeEffectUniforms.uAdjA2Loc = GLES30.glGetUniformLocation(p, "uAdjA2")
        gradeEffectUniforms.uFilterPresetIdLoc = GLES30.glGetUniformLocation(p, "uFilterPresetId")
        gradeEffectUniforms.uFilterIntensityLoc = GLES30.glGetUniformLocation(p, "uFilterIntensity")
        gradeEffectUniforms.uEffectPresetIdLoc = GLES30.glGetUniformLocation(p, "uEffectPresetId")
        gradeEffectUniforms.uEffectIntensityLoc = GLES30.glGetUniformLocation(p, "uEffectIntensity")
        gradeEffectUniforms.uTimeSecLoc = GLES30.glGetUniformLocation(p, "uTimeSec")
        return gradeEffectUniforms.programId
    }

    private fun getOrCompileTransitionProgram(): Int {
        if (transitionUniforms.programId != 0) return transitionUniforms.programId
        if (lazyCompileBudgetRemaining <= 0) return baseUniforms.programId
        lazyCompileBudgetRemaining--
        transitionUniforms.programId = buildProgram("Transition", vertexShaderSource, transitionFragmentShaderSource)
        val p = transitionUniforms.programId
        transitionUniforms.uTexALoc = GLES30.glGetUniformLocation(p, "uTexA")
        transitionUniforms.uTexBLoc = GLES30.glGetUniformLocation(p, "uTexB")
        transitionUniforms.uMatALoc = GLES30.glGetUniformLocation(p, "uMatA")
        transitionUniforms.uMatBLoc = GLES30.glGetUniformLocation(p, "uMatB")
        transitionUniforms.uCropALoc = GLES30.glGetUniformLocation(p, "uCropA")
        transitionUniforms.uCropBLoc = GLES30.glGetUniformLocation(p, "uCropB")
        transitionUniforms.uPLoc = GLES30.glGetUniformLocation(p, "uP")
        transitionUniforms.uTypeLoc = GLES30.glGetUniformLocation(p, "uType")
        transitionUniforms.uAspectLoc = GLES30.glGetUniformLocation(p, "uAspect")
        transitionUniforms.uAdjA0Loc = GLES30.glGetUniformLocation(p, "uAdjA0")
        transitionUniforms.uAdjA1Loc = GLES30.glGetUniformLocation(p, "uAdjA1")
        transitionUniforms.uAdjA2Loc = GLES30.glGetUniformLocation(p, "uAdjA2")
        transitionUniforms.uAdjB0Loc = GLES30.glGetUniformLocation(p, "uAdjB0")
        transitionUniforms.uAdjB1Loc = GLES30.glGetUniformLocation(p, "uAdjB1")
        transitionUniforms.uAdjB2Loc = GLES30.glGetUniformLocation(p, "uAdjB2")
        transitionUniforms.uTexelSizeLoc = GLES30.glGetUniformLocation(p, "uTexelSize")
        transitionUniforms.uFgNormMatALoc = GLES30.glGetUniformLocation(p, "uFgNormMatA")
        transitionUniforms.uFgNormMatBLoc = GLES30.glGetUniformLocation(p, "uFgNormMatB")
        transitionUniforms.uBgMatALoc = GLES30.glGetUniformLocation(p, "uBgMatA")
        transitionUniforms.uBgMatBLoc = GLES30.glGetUniformLocation(p, "uBgMatB")
        transitionUniforms.uBgModeALoc = GLES30.glGetUniformLocation(p, "uBgModeA")
        transitionUniforms.uBgModeBLoc = GLES30.glGetUniformLocation(p, "uBgModeB")
        transitionUniforms.uBgParamsALoc = GLES30.glGetUniformLocation(p, "uBgParamsA")
        transitionUniforms.uBgParamsBLoc = GLES30.glGetUniformLocation(p, "uBgParamsB")
        transitionUniforms.uOpacityALoc = GLES30.glGetUniformLocation(p, "uOpacityA")
        transitionUniforms.uOpacityBLoc = GLES30.glGetUniformLocation(p, "uOpacityB")
        transitionUniforms.uFilterPresetIdLoc = GLES30.glGetUniformLocation(p, "uFilterPresetId")
        transitionUniforms.uFilterIntensityLoc = GLES30.glGetUniformLocation(p, "uFilterIntensity")
        transitionUniforms.uEffectPresetIdLoc = GLES30.glGetUniformLocation(p, "uEffectPresetId")
        transitionUniforms.uEffectIntensityLoc = GLES30.glGetUniformLocation(p, "uEffectIntensity")
        transitionUniforms.uTimeSecLoc = GLES30.glGetUniformLocation(p, "uTimeSec")
        return transitionUniforms.programId
    }

    private fun getOrCompileOverlayProgram(): Int {
        if (overlayProgramId != 0) return overlayProgramId
        if (lazyCompileBudgetRemaining <= 0) return 0
        lazyCompileBudgetRemaining--
        overlayProgramId = buildProgram("Overlay", overlayVertexShaderSource, overlayFragmentShaderSource)
        uOverlayMatLoc = GLES30.glGetUniformLocation(overlayProgramId, "uOverlayMat")
        uOverlayTexLoc = GLES30.glGetUniformLocation(overlayProgramId, "uOverlayTex")
        uOverlayOpacityLoc = GLES30.glGetUniformLocation(overlayProgramId, "uOverlayOpacity")
        uCropBoundsLoc = GLES30.glGetUniformLocation(overlayProgramId, "uCropBounds")
        uMaskTypeLoc = GLES30.glGetUniformLocation(overlayProgramId, "uMaskType")
        uMaskFeatherLoc = GLES30.glGetUniformLocation(overlayProgramId, "uMaskFeather")
        uMaskInvertLoc = GLES30.glGetUniformLocation(overlayProgramId, "uMaskInvert")
        uChromaEnabledLoc = GLES30.glGetUniformLocation(overlayProgramId, "uChromaEnabled")
        uChromaKeyRgbLoc = GLES30.glGetUniformLocation(overlayProgramId, "uChromaKeyRgb")
        uChromaThresholdLoc = GLES30.glGetUniformLocation(overlayProgramId, "uChromaThreshold")
        uChromaBlendLoc = GLES30.glGetUniformLocation(overlayProgramId, "uChromaBlend")
        uAdjustBrightnessLoc = GLES30.glGetUniformLocation(overlayProgramId, "uAdjustBrightness")
        uAdjustContrastLoc = GLES30.glGetUniformLocation(overlayProgramId, "uAdjustContrast")
        uAdjustSaturationLoc = GLES30.glGetUniformLocation(overlayProgramId, "uAdjustSaturation")
        return overlayProgramId
    }

    private fun getOrCompileMosaicProgram(): Int {
        if (mosaicProgramId != 0) return mosaicProgramId
        if (lazyCompileBudgetRemaining <= 0) return 0
        lazyCompileBudgetRemaining--
        mosaicProgramId = buildProgram("Mosaic", mosaicVertexShaderSource, mosaicFragmentShaderSource)
        uMosaicMvpLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uMosaicMvp")
        uMosaicVideoTexLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uVideoTex")
        uMosaicVideoMatLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uVideoMat")
        uMosaicCropLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uCrop")
        uMosaicStyleLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uMosaicStyle")
        uMosaicIntensityLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uMosaicIntensity")
        uMosaicFeatherLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uMosaicFeather")
        uMosaicOpacityLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uOpacity")
        uMosaicTexelSizeLoc = GLES30.glGetUniformLocation(mosaicProgramId, "uTexelSize")
        return mosaicProgramId
    }

    private fun setupExternalTexture(textureId: Int) {
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun textureIdA(): Int = texAId
    fun textureIdB(): Int = texBId

    private fun hasActiveColorAdjust(spec: DrawSpec, useSlotB: Boolean): Boolean {
        val adj0 = if (useSlotB) spec.adjB0 else spec.adjA0
        val adj1 = if (useSlotB) spec.adjB1 else spec.adjA1
        val adj2 = if (useSlotB) spec.adjB2 else spec.adjA2
        if (adj0 != null && (adj0[0] != 0f || adj0[1] != 0f || adj0[2] != 0f || adj0[3] != 0f)) return true
        if (adj1 != null && (adj1[0] != 0f || adj1[1] != 0f || adj1[2] != 0f || adj1[3] != 0f)) return true
        if (adj2 != null && (adj2[0] != 0f || adj2[1] != 0f)) return true
        return false
    }

    fun draw(spec: DrawSpec) {
        lazyCompileBudgetRemaining = 1

        val isSlotBPrimary = (spec.opacityA <= 0.001f && spec.opacityB > 0.001f) ||
                (spec.transitionType == 1 && spec.easedP >= 0.999f)
        val isRealTransition = !isSlotBPrimary &&
                ((spec.transitionType > 0 && spec.easedP > 0.001f && spec.easedP < 0.999f) ||
                 (spec.type != TransitionType.NONE && spec.easedP > 0.001f && spec.easedP < 0.999f))

        if (isRealTransition) {
            val prog = getOrCompileTransitionProgram()
            if (prog == transitionUniforms.programId && prog != 0) {
                drawTransition(spec)
                return
            }
        }

        val hasGradeOrEffect = spec.filterPresetId != 0 || spec.effectPresetId != 0 || hasActiveColorAdjust(spec, isSlotBPrimary)
        if (hasGradeOrEffect) {
            val prog = getOrCompileGradeEffectProgram()
            if (prog == gradeEffectUniforms.programId && prog != 0) {
                drawGradeEffect(spec, isSlotBPrimary)
                return
            }
        }

        drawBase(spec, isSlotBPrimary)
    }

    private fun drawBase(spec: DrawSpec, isSlotBPrimary: Boolean) {
        val p = baseUniforms.programId
        GLES30.glUseProgram(p)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val activeTexId = if (isSlotBPrimary) texBId else texAId
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, activeTexId)
        GLES30.glUniform1i(baseUniforms.uTexALoc, 0)

        val mat = if (isSlotBPrimary) spec.matB else spec.matA
        val crop = if (isSlotBPrimary) spec.cropB else spec.cropA
        val opacity = if (isSlotBPrimary) spec.opacityB else spec.opacityA
        val fgNormMat = if (isSlotBPrimary) (spec.fgNormMatB ?: defaultIdentityMat) else (spec.fgNormMatA ?: defaultIdentityMat)
        val bgMat = if (isSlotBPrimary) (spec.bgMatB ?: defaultIdentityMat) else (spec.bgMatA ?: defaultIdentityMat)
        val bgMode = if (isSlotBPrimary) spec.bgModeB else spec.bgModeA
        val bgParams = if (isSlotBPrimary) (spec.bgParamsB ?: defaultBgParams) else (spec.bgParamsA ?: defaultBgParams)

        GLES30.glUniformMatrix4fv(baseUniforms.uMatALoc, 1, false, mat, 0)
        GLES30.glUniform2fv(baseUniforms.uCropALoc, 1, crop, 0)
        GLES30.glUniformMatrix4fv(baseUniforms.uFgNormMatALoc, 1, false, fgNormMat, 0)
        GLES30.glUniformMatrix4fv(baseUniforms.uBgMatALoc, 1, false, bgMat, 0)
        GLES30.glUniform1i(baseUniforms.uBgModeALoc, bgMode)
        GLES30.glUniform4fv(baseUniforms.uBgParamsALoc, 1, bgParams, 0)
        GLES30.glUniform1f(baseUniforms.uOpacityALoc, opacity)
        GLES30.glUniform2fv(baseUniforms.uTexelSizeLoc, 1, spec.texelSize ?: defaultTexelSize, 0)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
    }

    private fun drawGradeEffect(spec: DrawSpec, isSlotBPrimary: Boolean) {
        val p = gradeEffectUniforms.programId
        GLES30.glUseProgram(p)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val activeTexId = if (isSlotBPrimary) texBId else texAId
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, activeTexId)
        GLES30.glUniform1i(gradeEffectUniforms.uTexALoc, 0)

        val mat = if (isSlotBPrimary) spec.matB else spec.matA
        val crop = if (isSlotBPrimary) spec.cropB else spec.cropA
        val opacity = if (isSlotBPrimary) spec.opacityB else spec.opacityA
        val fgNormMat = if (isSlotBPrimary) (spec.fgNormMatB ?: defaultIdentityMat) else (spec.fgNormMatA ?: defaultIdentityMat)
        val bgMat = if (isSlotBPrimary) (spec.bgMatB ?: defaultIdentityMat) else (spec.bgMatA ?: defaultIdentityMat)
        val bgMode = if (isSlotBPrimary) spec.bgModeB else spec.bgModeA
        val bgParams = if (isSlotBPrimary) (spec.bgParamsB ?: defaultBgParams) else (spec.bgParamsA ?: defaultBgParams)

        GLES30.glUniformMatrix4fv(gradeEffectUniforms.uMatALoc, 1, false, mat, 0)
        GLES30.glUniform2fv(gradeEffectUniforms.uCropALoc, 1, crop, 0)
        GLES30.glUniformMatrix4fv(gradeEffectUniforms.uFgNormMatALoc, 1, false, fgNormMat, 0)
        GLES30.glUniformMatrix4fv(gradeEffectUniforms.uBgMatALoc, 1, false, bgMat, 0)
        GLES30.glUniform1i(gradeEffectUniforms.uBgModeALoc, bgMode)
        GLES30.glUniform4fv(gradeEffectUniforms.uBgParamsALoc, 1, bgParams, 0)
        GLES30.glUniform1f(gradeEffectUniforms.uOpacityALoc, opacity)
        GLES30.glUniform2fv(gradeEffectUniforms.uTexelSizeLoc, 1, spec.texelSize ?: defaultTexelSize, 0)

        val adj0 = if (isSlotBPrimary) (spec.adjB0 ?: defaultAdj) else (spec.adjA0 ?: defaultAdj)
        val adj1 = if (isSlotBPrimary) (spec.adjB1 ?: defaultAdj) else (spec.adjA1 ?: defaultAdj)
        val adj2 = if (isSlotBPrimary) (spec.adjB2 ?: defaultAdj) else (spec.adjA2 ?: defaultAdj)
        GLES30.glUniform4fv(gradeEffectUniforms.uAdjA0Loc, 1, adj0, 0)
        GLES30.glUniform4fv(gradeEffectUniforms.uAdjA1Loc, 1, adj1, 0)
        GLES30.glUniform4fv(gradeEffectUniforms.uAdjA2Loc, 1, adj2, 0)
        GLES30.glUniform1i(gradeEffectUniforms.uFilterPresetIdLoc, spec.filterPresetId)
        GLES30.glUniform1f(gradeEffectUniforms.uFilterIntensityLoc, spec.filterIntensity)
        GLES30.glUniform1i(gradeEffectUniforms.uEffectPresetIdLoc, spec.effectPresetId)
        GLES30.glUniform1f(gradeEffectUniforms.uEffectIntensityLoc, spec.effectIntensity)
        GLES30.glUniform1f(gradeEffectUniforms.uTimeSecLoc, spec.timeSec)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
    }

    private fun drawTransition(spec: DrawSpec) {
        val p = transitionUniforms.programId
        GLES30.glUseProgram(p)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texAId)
        GLES30.glUniform1i(transitionUniforms.uTexALoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texBId)
        GLES30.glUniform1i(transitionUniforms.uTexBLoc, 1)

        GLES30.glUniformMatrix4fv(transitionUniforms.uMatALoc, 1, false, spec.matA, 0)
        GLES30.glUniformMatrix4fv(transitionUniforms.uMatBLoc, 1, false, spec.matB, 0)
        GLES30.glUniform2fv(transitionUniforms.uCropALoc, 1, spec.cropA, 0)
        GLES30.glUniform2fv(transitionUniforms.uCropBLoc, 1, spec.cropB, 0)
        GLES30.glUniform1f(transitionUniforms.uPLoc, spec.easedP)

        val glType = if (spec.transitionType >= 0) {
            spec.transitionType
        } else {
            when (spec.type) {
                TransitionType.NONE -> 0
                TransitionType.SLIDE -> 4
                TransitionType.CIRCLE -> 8
                TransitionType.ZOOM -> 6
                TransitionType.FADE -> 1
            }
        }
        GLES30.glUniform1i(transitionUniforms.uTypeLoc, glType)
        GLES30.glUniform1f(transitionUniforms.uAspectLoc, spec.aspectRatio)
        GLES30.glUniform4fv(transitionUniforms.uAdjA0Loc, 1, spec.adjA0 ?: defaultAdj, 0)
        GLES30.glUniform4fv(transitionUniforms.uAdjA1Loc, 1, spec.adjA1 ?: defaultAdj, 0)
        GLES30.glUniform4fv(transitionUniforms.uAdjA2Loc, 1, spec.adjA2 ?: defaultAdj, 0)
        GLES30.glUniform4fv(transitionUniforms.uAdjB0Loc, 1, spec.adjB0 ?: defaultAdj, 0)
        GLES30.glUniform4fv(transitionUniforms.uAdjB1Loc, 1, spec.adjB1 ?: defaultAdj, 0)
        GLES30.glUniform4fv(transitionUniforms.uAdjB2Loc, 1, spec.adjB2 ?: defaultAdj, 0)
        GLES30.glUniform2fv(transitionUniforms.uTexelSizeLoc, 1, spec.texelSize ?: defaultTexelSize, 0)
        GLES30.glUniformMatrix4fv(transitionUniforms.uFgNormMatALoc, 1, false, spec.fgNormMatA ?: defaultIdentityMat, 0)
        GLES30.glUniformMatrix4fv(transitionUniforms.uFgNormMatBLoc, 1, false, spec.fgNormMatB ?: defaultIdentityMat, 0)
        GLES30.glUniformMatrix4fv(transitionUniforms.uBgMatALoc, 1, false, spec.bgMatA ?: defaultIdentityMat, 0)
        GLES30.glUniformMatrix4fv(transitionUniforms.uBgMatBLoc, 1, false, spec.bgMatB ?: defaultIdentityMat, 0)
        GLES30.glUniform1i(transitionUniforms.uBgModeALoc, spec.bgModeA)
        GLES30.glUniform1i(transitionUniforms.uBgModeBLoc, spec.bgModeB)
        GLES30.glUniform4fv(transitionUniforms.uBgParamsALoc, 1, spec.bgParamsA ?: defaultBgParams, 0)
        GLES30.glUniform4fv(transitionUniforms.uBgParamsBLoc, 1, spec.bgParamsB ?: defaultBgParams, 0)
        GLES30.glUniform1f(transitionUniforms.uOpacityALoc, spec.opacityA)
        GLES30.glUniform1f(transitionUniforms.uOpacityBLoc, spec.opacityB)
        GLES30.glUniform1i(transitionUniforms.uFilterPresetIdLoc, spec.filterPresetId)
        GLES30.glUniform1f(transitionUniforms.uFilterIntensityLoc, spec.filterIntensity)
        GLES30.glUniform1i(transitionUniforms.uEffectPresetIdLoc, spec.effectPresetId)
        GLES30.glUniform1f(transitionUniforms.uEffectIntensityLoc, spec.effectIntensity)
        GLES30.glUniform1f(transitionUniforms.uTimeSecLoc, spec.timeSec)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
    }

    fun drawOverlayQuad(
        texId: Int,
        texWidth: Int,
        texHeight: Int,
        transform: ClipTransform,
        opacity: Float = 1.0f,
        displayWidth: Int = 1080,
        displayHeight: Int = 1920,
        cropLeft: Float = 0.0f,
        cropTop: Float = 0.0f,
        cropRight: Float = 1.0f,
        cropBottom: Float = 1.0f,
        maskType: Int = 0,
        maskFeather: Float = 0.05f,
        maskInvert: Boolean = false,
        chromaEnabled: Boolean = false,
        chromaColorArgb: Int = 0xFF00FF00.toInt(),
        chromaThreshold: Float = 0.25f,
        chromaBlend: Float = 0.10f,
        adjustBrightness: Float = 0.0f,
        adjustContrast: Float = 1.0f,
        adjustSaturation: Float = 1.0f
    ) {
        if (texId <= 0) return
        val effectiveOpacity = (transform.opacity * opacity).coerceIn(0f, 1f)
        if (effectiveOpacity <= 0.001f) return

        if (isOverlayOffScreen(transform, texWidth, texHeight, displayWidth, displayHeight, cropLeft, cropTop, cropRight, cropBottom)) {
            return
        }

        val prog = getOrCompileOverlayProgram()
        if (prog == 0) return

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(uOverlayTexLoc, 0)
        GLES30.glUniform1f(uOverlayOpacityLoc, effectiveOpacity)

        GLES30.glUniform4f(uCropBoundsLoc, cropLeft, cropTop, cropRight, cropBottom)
        GLES30.glUniform1i(uMaskTypeLoc, maskType)
        GLES30.glUniform1f(uMaskFeatherLoc, maskFeather)
        GLES30.glUniform1i(uMaskInvertLoc, if (maskInvert) 1 else 0)

        GLES30.glUniform1i(uChromaEnabledLoc, if (chromaEnabled) 1 else 0)
        val r = ((chromaColorArgb shr 16) and 0xFF) / 255.0f
        val g = ((chromaColorArgb shr 8) and 0xFF) / 255.0f
        val b = (chromaColorArgb and 0xFF) / 255.0f
        GLES30.glUniform3f(uChromaKeyRgbLoc, r, g, b)
        GLES30.glUniform1f(uChromaThresholdLoc, chromaThreshold)
        GLES30.glUniform1f(uChromaBlendLoc, chromaBlend)

        GLES30.glUniform1f(uAdjustBrightnessLoc, adjustBrightness)
        GLES30.glUniform1f(uAdjustContrastLoc, adjustContrast)
        GLES30.glUniform1f(uAdjustSaturationLoc, adjustSaturation)

        computeOverlayMvp(overlayMvp, transform, texWidth, texHeight, displayWidth, displayHeight, cropLeft, cropTop, cropRight, cropBottom)
        GLES30.glUniformMatrix4fv(uOverlayMatLoc, 1, false, overlayMvp, 0)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun drawOverlayQuad(
        texId: Int,
        transform: ClipTransform,
        opacity: Float = 1.0f,
        displayWidth: Int = 1080,
        displayHeight: Int = 1920
    ) {
        drawOverlayQuad(texId, 1, 1, transform, opacity, displayWidth, displayHeight)
    }

    fun drawMosaicRegion(
        videoTexId: Int,
        videoMat: FloatArray,
        crop: FloatArray = defaultCrop,
        transform: ClipTransform,
        mosaicStyle: Int = 0,
        mosaicIntensity: Float = 0.5f,
        mosaicFeather: Float = 0.05f,
        opacity: Float = 1.0f,
        displayWidth: Int = 1080,
        displayHeight: Int = 1920
    ) {
        val effectiveOpacity = (transform.opacity * opacity).coerceIn(0f, 1f)
        if (effectiveOpacity <= 0.001f) return
        if (videoTexId <= 0) return

        val prog = getOrCompileMosaicProgram()
        if (prog == 0) return

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(prog)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES30.glUniform1i(uMosaicVideoTexLoc, 0)

        GLES30.glUniformMatrix4fv(uMosaicVideoMatLoc, 1, false, videoMat, 0)
        GLES30.glUniform2fv(uMosaicCropLoc, 1, crop, 0)
        GLES30.glUniform1i(uMosaicStyleLoc, mosaicStyle)
        GLES30.glUniform1f(uMosaicIntensityLoc, mosaicIntensity.coerceIn(0.1f, 1.0f))
        GLES30.glUniform1f(uMosaicFeatherLoc, mosaicFeather.coerceIn(0.0f, 0.3f))
        GLES30.glUniform1f(uMosaicOpacityLoc, effectiveOpacity)

        val tw = if (displayWidth > 0) 1f / displayWidth.toFloat() else 1f / 1080f
        val th = if (displayHeight > 0) 1f / displayHeight.toFloat() else 1f / 1920f
        mosaicTexelSize[0] = tw
        mosaicTexelSize[1] = th
        GLES30.glUniform2fv(uMosaicTexelSizeLoc, 1, mosaicTexelSize, 0)

        computeOverlayMvp(overlayMvp, transform, 1, 1, displayWidth, displayHeight, 0f, 0f, 1f, 1f)
        GLES30.glUniformMatrix4fv(uMosaicMvpLoc, 1, false, overlayMvp, 0)

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glUseProgram(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        if (baseUniforms.programId != 0) {
            GLES30.glDeleteProgram(baseUniforms.programId)
            baseUniforms.programId = 0
        }
        if (gradeEffectUniforms.programId != 0) {
            GLES30.glDeleteProgram(gradeEffectUniforms.programId)
            gradeEffectUniforms.programId = 0
        }
        if (transitionUniforms.programId != 0) {
            GLES30.glDeleteProgram(transitionUniforms.programId)
            transitionUniforms.programId = 0
        }
        if (overlayProgramId != 0) {
            GLES30.glDeleteProgram(overlayProgramId)
            overlayProgramId = 0
        }
        if (mosaicProgramId != 0) {
            GLES30.glDeleteProgram(mosaicProgramId)
            mosaicProgramId = 0
        }
        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (vboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
            vboId = 0
        }
        if (texAId != 0 || texBId != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(texAId, texBId), 0)
            texAId = 0
            texBId = 0
        }
    }

    private fun ensureGlStrings() {
        if (glRenderer.isEmpty()) {
            glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unknown"
            glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "unknown"
        }
    }

    private fun getCacheFile(name: String, vsSrc: String, fsSrc: String): File? {
        val dir = cacheDir ?: return null
        if (!dir.exists()) dir.mkdirs()
        ensureGlStrings()
        val combined = "$name:${vsSrc.hashCode()}:${fsSrc.hashCode()}:$glRenderer:$glVersion:${android.os.Build.FINGERPRINT}"
        val hash = combined.hashCode().toUInt().toString(16)
        return File(dir, "fc_shader_${name}_$hash.bin")
    }

    private fun tryLoadFromDisk(program: Int, cacheFile: File): Boolean {
        if (!cacheFile.exists()) return false
        return try {
            val bytes = cacheFile.readBytes()
            if (bytes.size < 4) return false
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            val binaryFormat = buffer.int
            val binaryLength = bytes.size - 4
            val payload = ByteBuffer.allocateDirect(binaryLength).order(ByteOrder.nativeOrder())
            payload.put(bytes, 4, binaryLength)
            payload.position(0)
            GLES30.glProgramBinary(program, binaryFormat, payload, binaryLength)
            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
            linkStatus[0] == GLES30.GL_TRUE
        } catch (e: Exception) {
            android.util.Log.w("FlowCutShader", "Disk cache read failed for ${cacheFile.name}: ${e.message}")
            false
        }
    }

    private fun saveToDisk(program: Int, cacheFile: File) {
        try {
            val length = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_PROGRAM_BINARY_LENGTH, length, 0)
            if (length[0] <= 0) return
            val binary = ByteBuffer.allocateDirect(length[0]).order(ByteOrder.nativeOrder())
            val lengthOut = IntArray(1)
            val formatOut = IntArray(1)
            GLES30.glGetProgramBinary(program, length[0], lengthOut, 0, formatOut, 0, binary)
            if (lengthOut[0] > 0) {
                val bytes = ByteArray(4 + lengthOut[0])
                val wrap = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                wrap.putInt(formatOut[0])
                binary.position(0)
                binary.get(bytes, 4, lengthOut[0])
                cacheFile.writeBytes(bytes)
                android.util.Log.d("FlowCutShader", "Saved binary cache ${cacheFile.name} (${lengthOut[0]} bytes)")
            }
        } catch (e: Exception) {
            android.util.Log.w("FlowCutShader", "Disk cache save failed for ${cacheFile.name}: ${e.message}")
        }
    }

    private fun buildProgram(name: String, vertexSrc: String, fragmentSrc: String): Int {
        val program = GLES30.glCreateProgram()
        val cacheFile = getCacheFile(name, vertexSrc, fragmentSrc)
        val t0 = android.os.SystemClock.elapsedRealtime()

        if (cacheFile != null && tryLoadFromDisk(program, cacheFile)) {
            val dt = android.os.SystemClock.elapsedRealtime() - t0
            android.util.Log.d("FlowCutShader", "$name loaded from binary cache in $dt ms")
            return program
        }

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)

        val tLink = android.os.SystemClock.elapsedRealtime()
        GLES30.glLinkProgram(program)
        val linkDt = android.os.SystemClock.elapsedRealtime() - tLink
        android.util.Log.d("FlowCutShader", "$name link $linkDt ms")

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Error linking $name program: $log")
        }

        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        if (cacheFile != null) {
            saveToDisk(program, cacheFile)
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Error compiling shader ($type): $log")
        }
        return shader
    }

    companion object {
        @Volatile
        var canvasAspect: Float = 9.0f / 16.0f

        fun calculateCrop(clip: Clip, canvasAspectRatio: Float = canvasAspect): FloatArray {
            val aspect = clip.displayWidth.toFloat() / clip.displayHeight.toFloat()
            val target = if (canvasAspectRatio > 0f) canvasAspectRatio else (9f / 16f)
            return if (aspect > target) {
                floatArrayOf(target / aspect, 1.0f)
            } else {
                floatArrayOf(1.0f, aspect / target)
            }
        }

        fun isOverlayOffScreen(
            transform: ClipTransform,
            texWidth: Int = 1,
            texHeight: Int = 1,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920,
            cropLeft: Float = 0.0f,
            cropTop: Float = 0.0f,
            cropRight: Float = 1.0f,
            cropBottom: Float = 1.0f,
            canvasAspect: Float = Companion.canvasAspect
        ): Boolean {
            val aScreen = if (displayHeight > 0) displayWidth.toFloat() / displayHeight.toFloat() else if (canvasAspect > 0f) canvasAspect else (9.0f / 16.0f)
            val tw = if (texWidth > 0) texWidth.toFloat() else 1f
            val th = if (texHeight > 0) texHeight.toFloat() else 1f
            val cropW = (cropRight - cropLeft).coerceIn(0.01f, 1.0f)
            val cropH = (cropBottom - cropTop).coerceIn(0.01f, 1.0f)
            val aTex = ((tw * cropW) / (th * cropH)).coerceIn(0.01f, 100f)

            var hw = 0.6f
            var hh = hw / aTex
            if (hh * aScreen > 0.6f) {
                val factor = 0.6f / (hh * aScreen)
                hw *= factor
                hh *= factor
            }

            val scale = transform.scale.coerceIn(0.01f, 10.0f)
            val diag = kotlin.math.sqrt(hw * hw + hh * hh) * scale
            val rx = diag
            val ry = diag * aScreen

            val cx = transform.panX * 2.0f
            val cy = transform.panY * 2.0f

            val left = cx - rx
            val right = cx + rx
            val bottom = cy - ry
            val top = cy + ry

            return right < -1.0f || left > 1.0f || top < -1.0f || bottom > 1.0f
        }

        fun computeOverlayMvp(
            outMvp: FloatArray,
            transform: ClipTransform,
            texWidth: Int = 1,
            texHeight: Int = 1,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920,
            cropLeft: Float = 0.0f,
            cropTop: Float = 0.0f,
            cropRight: Float = 1.0f,
            cropBottom: Float = 1.0f,
            canvasAspect: Float = Companion.canvasAspect
        ) {
            val aScreen = if (displayHeight > 0) displayWidth.toFloat() / displayHeight.toFloat() else if (canvasAspect > 0f) canvasAspect else (9.0f / 16.0f)
            val tw = if (texWidth > 0) texWidth.toFloat() else 1f
            val th = if (texHeight > 0) texHeight.toFloat() else 1f
            val cropW = (cropRight - cropLeft).coerceIn(0.01f, 1.0f)
            val cropH = (cropBottom - cropTop).coerceIn(0.01f, 1.0f)
            val aTex = ((tw * cropW) / (th * cropH)).coerceIn(0.01f, 100f)

            var hw = 0.6f
            var hh = hw / aTex
            if (hh * aScreen > 0.6f) {
                val factor = 0.6f / (hh * aScreen)
                hw *= factor
                hh *= factor
            }

            val scale = transform.scale.coerceIn(0.01f, 10.0f)

            android.opengl.Matrix.setIdentityM(outMvp, 0)
            android.opengl.Matrix.translateM(outMvp, 0, transform.panX * 2.0f, transform.panY * 2.0f, 0f)
            android.opengl.Matrix.scaleM(outMvp, 0, 1.0f, aScreen, 1.0f)
            if (transform.rotationDeg != 0f) {
                android.opengl.Matrix.rotateM(outMvp, 0, -transform.rotationDeg, 0f, 0f, 1f)
            }
            if (transform.isFlippedH) {
                android.opengl.Matrix.scaleM(outMvp, 0, -1f, 1f, 1f)
            }
            android.opengl.Matrix.scaleM(outMvp, 0, hw * scale, hh * scale, 1f)
        }
    }
}
