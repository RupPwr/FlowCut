package com.flowcut.app.engine

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.flowcut.app.model.Clip
import com.flowcut.app.model.TransitionType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class DrawSpec(
    var type: TransitionType = TransitionType.NONE,
    var easedP: Float = 0f,
    var matA: FloatArray = FloatArray(16),
    var matB: FloatArray = FloatArray(16),
    var cropA: FloatArray = FloatArray(2),
    var cropB: FloatArray = FloatArray(2),
    var adjA0: FloatArray? = null,
    var adjA1: FloatArray? = null,
    var adjA2: FloatArray? = null,
    var adjB0: FloatArray? = null,
    var adjB1: FloatArray? = null,
    var adjB2: FloatArray? = null,
    var texelSize: FloatArray? = null
)

class TransitionRenderer {

    private var programId = 0
    private var vaoId = 0
    private var vboId = 0
    private var texAId = 0
    private var texBId = 0

    // Uniform locations
    private var uTexALoc = -1
    private var uTexBLoc = -1
    private var uMatALoc = -1
    private var uMatBLoc = -1
    private var uCropALoc = -1
    private var uCropBLoc = -1
    private var uPLoc = -1
    private var uTypeLoc = -1
    private var uAspectLoc = -1
    private var uAdjA0Loc = -1
    private var uAdjA1Loc = -1
    private var uAdjA2Loc = -1
    private var uAdjB0Loc = -1
    private var uAdjB1Loc = -1
    private var uAdjB2Loc = -1
    private var uTexelSizeLoc = -1

    // Pre-allocated fallback arrays for zero allocations (Rule R10)
    private val defaultAdj = FloatArray(4)
    private val defaultTexelSize = floatArrayOf(1f / 1080f, 1f / 1920f)

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

    private val fragmentShaderSource = """
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

        in vec2 vUV;
        out vec4 outColor;

        vec4 applyColorAdjust(samplerExternalOES tex, vec2 uv, vec4 adj0, vec4 adj1, vec4 adj2, vec2 texelSize) {
            // Fast Path: If all adjustments for this slot are 0.0, return raw sampled texture color immediately
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

            // Sharpness (sharpness > 0.001): 5-tap unsharp mask
            if (sharpness > 0.001) {
                vec3 nLeft  = texture(tex, uv - vec2(texelSize.x, 0.0)).rgb;
                vec3 nRight = texture(tex, uv + vec2(texelSize.x, 0.0)).rgb;
                vec3 nDown  = texture(tex, uv - vec2(0.0, texelSize.y)).rgb;
                vec3 nUp    = texture(tex, uv + vec2(0.0, texelSize.y)).rgb;
                vec3 neighbors = nLeft + nRight + nDown + nUp;
                color.rgb += (color.rgb * 4.0 - neighbors) * (sharpness * 1.2);
            }

            // Exposure & Brightness
            color.rgb *= exp2(exposure * 1.2);
            color.rgb += brightness * 0.35;

            // Highlights & Shadows
            float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            float shadowWeight = (1.0 - clamp(luma, 0.0, 1.0)) * (1.0 - clamp(luma, 0.0, 1.0));
            float highlightWeight = clamp(luma, 0.0, 1.0) * clamp(luma, 0.0, 1.0);
            color.rgb += (shadows * shadowWeight + highlights * highlightWeight) * 0.35;

            // Contrast: Pivot around mid-gray 0.5
            color.rgb = (color.rgb - 0.5) * (1.0 + contrast * 0.85) + 0.5;

            // Saturation: Mix between vec3(luma) and color.rgb by (1.0 + saturation)
            float lumaAfter = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            color.rgb = mix(vec3(lumaAfter), color.rgb, max(0.0, 1.0 + saturation));

            // Warmth (Temp) & Tint
            color.r += warmth * 0.12;
            color.b -= warmth * 0.12;
            color.g -= tint * 0.10;
            color.r += tint * 0.05;
            color.b += tint * 0.05;

            // Vignette: radial distance from uv center (0.5, 0.5)
            float dist = length(uv - vec2(0.5, 0.5));
            float vigFactor = smoothstep(0.25, 0.85, dist);
            if (vignette < 0.0) {
                color.rgb *= (1.0 + vignette * vigFactor);
            } else if (vignette > 0.0) {
                color.rgb = mix(color.rgb, vec3(1.0), vignette * vigFactor * 0.8);
            }

            // Clamp final RGB cleanly to [0.0, 1.0]
            color.rgb = clamp(color.rgb, 0.0, 1.0);
            return color;
        }

        vec4 sampleClipA(vec2 uv) {
            uv = (uv - 0.5) * uCropA + 0.5;
            uv = (uMatA * vec4(clamp(uv, 0.0, 1.0), 0.0, 1.0)).xy;
            return applyColorAdjust(uTexA, uv, uAdjA0, uAdjA1, uAdjA2, uTexelSize);
        }
        vec4 sampleClipB(vec2 uv) {
            uv = (uv - 0.5) * uCropB + 0.5;
            uv = (uMatB * vec4(clamp(uv, 0.0, 1.0), 0.0, 1.0)).xy;
            return applyColorAdjust(uTexB, uv, uAdjB0, uAdjB1, uAdjB2, uTexelSize);
        }

        vec4 sA(vec2 uv) { return sampleClipA(uv); }
        vec4 sB(vec2 uv) { return sampleClipB(uv); }

        void main() {
            vec4 c;
            if (uType == 0) {
                c = sA(vUV);
            } else if (uType == 1) {
                // SLIDE: B comes from right, A moves 30% left
                if (vUV.x > 1.0 - uP) {
                    c = sB(vec2(vUV.x - (1.0 - uP), vUV.y));
                } else {
                    c = sA(vec2(vUV.x + 0.3 * uP, vUV.y));
                }
            } else if (uType == 2) {
                // CIRCLE: circular reveal from center
                vec2 d = (vUV - 0.5) * vec2(uAspect, 1.0);
                float maxR = length(vec2(0.5 * uAspect, 0.5));
                float r = uP * (maxR + 0.01);
                float m = (uP <= 0.0) ? 0.0 : 1.0 - smoothstep(r - 0.004, r + 0.004, length(d));
                c = mix(sA(vUV), sB(vUV), m);
            } else if (uType == 3) {
                // ZOOM: A zooms in and fades out, B zooms out from 1.4x to 1.0x and fades in
                float sa = 1.0 + 0.8 * uP;
                float sb = 1.4 - 0.4 * uP;
                vec4 a = sA((vUV - 0.5) / sa + 0.5);
                vec4 b = sB((vUV - 0.5) / sb + 0.5);
                c = mix(a, b, uP);
            } else {
                // FADE
                c = mix(sA(vUV), sB(vUV), uP);
            }
            outColor = vec4(c.rgb, 1.0);
        }
    """.trimIndent()

    // 4 vertices (Pos: x, y; UV: u, v)
    // Triangle strip: (-1,-1), (1,-1), (-1,1), (1,1)
    private val quadVertices = floatArrayOf(
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f
    )

    fun init() {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderSource)
        programId = createProgram(vertexShader, fragmentShader)

        // Locations
        uTexALoc = GLES30.glGetUniformLocation(programId, "uTexA")
        uTexBLoc = GLES30.glGetUniformLocation(programId, "uTexB")
        uMatALoc = GLES30.glGetUniformLocation(programId, "uMatA")
        uMatBLoc = GLES30.glGetUniformLocation(programId, "uMatB")
        uCropALoc = GLES30.glGetUniformLocation(programId, "uCropA")
        uCropBLoc = GLES30.glGetUniformLocation(programId, "uCropB")
        uPLoc = GLES30.glGetUniformLocation(programId, "uP")
        uTypeLoc = GLES30.glGetUniformLocation(programId, "uType")
        uAspectLoc = GLES30.glGetUniformLocation(programId, "uAspect")
        uAdjA0Loc = GLES30.glGetUniformLocation(programId, "uAdjA0")
        uAdjA1Loc = GLES30.glGetUniformLocation(programId, "uAdjA1")
        uAdjA2Loc = GLES30.glGetUniformLocation(programId, "uAdjA2")
        uAdjB0Loc = GLES30.glGetUniformLocation(programId, "uAdjB0")
        uAdjB1Loc = GLES30.glGetUniformLocation(programId, "uAdjB1")
        uAdjB2Loc = GLES30.glGetUniformLocation(programId, "uAdjB2")
        uTexelSizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")

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

    fun draw(spec: DrawSpec) {
        GLES30.glUseProgram(programId)

        // Bind Texture A to Unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texAId)
        GLES30.glUniform1i(uTexALoc, 0)

        // Bind Texture B to Unit 1 (or dummy A if none)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        if (spec.type != TransitionType.NONE) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texBId)
        } else {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texAId)
        }
        GLES30.glUniform1i(uTexBLoc, 1)

        // Set uniforms
        GLES30.glUniformMatrix4fv(uMatALoc, 1, false, spec.matA, 0)
        GLES30.glUniformMatrix4fv(uMatBLoc, 1, false, spec.matB, 0)
        GLES30.glUniform2fv(uCropALoc, 1, spec.cropA, 0)
        GLES30.glUniform2fv(uCropBLoc, 1, spec.cropB, 0)
        GLES30.glUniform1f(uPLoc, spec.easedP)
        GLES30.glUniform1i(uTypeLoc, spec.type.glId)
        GLES30.glUniform1f(uAspectLoc, 9.0f / 16.0f)
        GLES30.glUniform4fv(uAdjA0Loc, 1, spec.adjA0 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjA1Loc, 1, spec.adjA1 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjA2Loc, 1, spec.adjA2 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjB0Loc, 1, spec.adjB0 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjB1Loc, 1, spec.adjB1 ?: defaultAdj, 0)
        GLES30.glUniform4fv(uAdjB2Loc, 1, spec.adjB2 ?: defaultAdj, 0)
        GLES30.glUniform2fv(uTexelSizeLoc, 1, spec.texelSize ?: defaultTexelSize, 0)

        // Draw quad
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glUseProgram(0)
    }

    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
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

    private fun createProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Error linking program: $log")
        }
        return program
    }

    companion object {
        fun calculateCrop(clip: Clip): FloatArray {
            val aspect = clip.displayWidth.toFloat() / clip.displayHeight.toFloat()
            val target = 9f / 16f
            return if (aspect > target) {
                floatArrayOf(target / aspect, 1.0f)
            } else {
                floatArrayOf(1.0f, aspect / target)
            }
        }
    }
}

