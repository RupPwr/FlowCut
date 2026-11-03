package com.flowcut.app.engine

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.flowcut.app.model.Clip
import com.flowcut.app.model.TransitionType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class DrawSpec(
    val type: TransitionType,
    val easedP: Float,
    val matA: FloatArray,
    val matB: FloatArray,
    val cropA: FloatArray,
    val cropB: FloatArray
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

        in vec2 vUV;
        out vec4 outColor;

        vec4 sA(vec2 uv) {
            uv = (uv - 0.5) * uCropA + 0.5;
            uv = (uMatA * vec4(clamp(uv, 0.0, 1.0), 0.0, 1.0)).xy;
            return texture(uTexA, uv);
        }
        vec4 sB(vec2 uv) {
            uv = (uv - 0.5) * uCropB + 0.5;
            uv = (uMatB * vec4(clamp(uv, 0.0, 1.0), 0.0, 1.0)).xy;
            return texture(uTexB, uv);
        }

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

