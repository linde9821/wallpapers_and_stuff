package countour.stacking

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.extra.marchingsquares.findContours
import org.openrndr.extra.noise.Random.perlin
import org.openrndr.extra.noise.Random.simplex
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.videoprofiles.ProresProfile
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.map
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun main() =
    application {
        configure {
            width = 800
            height = 1200
        }
        program {
            val numLayers = 50
            val spacing = 30.0
            val searchArea = Rectangle(0.0, 0.0, 800.0, 650.0)

            val totalDuration = 7.0 // Duration of video in seconds
            val fps = 60

            extend(ScreenRecorder()) {
                profile =
                    ProresProfile().apply {
                        this.profile = ProresProfile.Profile.HQ4444
                    }
                frameRate = fps
                name = "input.mp4"
            }

            val customGradShader =
                shadeStyle {
                    fragmentTransform =
                        """
                        vec2 uv = c_boundsPosition.xy; 
                        
                        // Use sine/cosine inputs from p_time to ensure shader loops perfectly
                        float wave = sin(uv.x * 4.0 + p_timeX) * cos(uv.y * 4.0 - p_timeY);
                        wave += sin(uv.y * 6.0 + p_timeX);
                        
                        float mixAmount = clamp(wave * 0.25 + 0.5, 0.0, 1.0);
                        vec4 gradientColor = mix(p_colorA, p_colorB, mixAmount);
                        x_fill.rgb = gradientColor.rgb;
                        """.trimIndent()

                    parameter("timeX", 0.0)
                    parameter("timeY", 0.0)
                    parameter("colorA", ColorRGBa.RED * 1.2)
                    parameter("colorB", ColorRGBa.CYAN * 1.2)
                }

            extend {
                println("$frameCount out of ${totalDuration * fps}")

                if (frameCount >= totalDuration * fps) {
                    application.exit()
                }

                drawer.clear(ColorRGBa.fromHex("111116"))

                // 1. DETERMINISTIC VIDEO CLOCK:
                // Calculate time explicitly based on the frameCount to guarantee 60fps export precision
                val videoSeconds = frameCount.toDouble() / fps
                val currentProgress = videoSeconds / totalDuration
                val loopAngle = currentProgress * 2.0 * PI

                // Generate looping coordinates for noise fields
                val loopX = sin(loopAngle)
                val loopY = cos(loopAngle)

                val zoom = 2.6
                drawer.ortho(
                    -width / 2.0 * zoom,
                    width / 2.0 * zoom,
                    -height / 2.0 * zoom,
                    height / 2.0 * zoom,
                    -2000.0,
                    2000.0,
                )
                drawer.depthWrite = true

                drawer.isolated {
                    drawer.translate(0.0, -150.0, 0.0)
                    drawer.rotate(Vector3.UNIT_X, 60.0)
                    drawer.rotate(Vector3.UNIT_Z, 45.0)

                    drawer.translate(0.0, 0.0, -(numLayers * spacing) / 2.0)

                    // 2. ONE PERFECT ROTATION:
                    // Automatically hits exactly 360 degrees when currentProgress reaches 1.0 (at 8 seconds)
                    drawer.rotate(Vector3.UNIT_Z, currentProgress * 360.0)

                    drawer.translate(-searchArea.width / 2.0, -searchArea.height / 2.0, 0.0)

                    for (z in numLayers downTo 0) {
                        drawer.isolated {
                            drawer.translate(0.0, 0.0, z * spacing)

                            val noiseFunction = { v: Vector2 ->
                                val noiseScale = 0.002

                                // We append a small z-offset (z * 0.05) to loopX and loopY
                                // so that each vertical layer samples a slightly shifted slice of the 4D time circle.
                                val timeOffsetX = loopX * 0.8 + (z * 0.05)
                                val timeOffsetY = loopY * 0.8 + (z * 0.05)

                                // Evaluate 4D Simplex Noise: Space (X, Y) + Looping Time (Z, W)
                                val n =
                                    simplex(
                                        x = v.x * noiseScale,
                                        y = v.y * noiseScale,
                                        z = timeOffsetX,
                                        w = timeOffsetY,
                                    )

                                n - 0.1
                            }

                            val areaScale = simplex(33, z * 0.01, loopY * 0.3).map(-1.0, 1.0, 0.15, 1.8)

                            val contours =
                                findContours(
                                    f = noiseFunction,
                                    area = searchArea * areaScale,
                                    cellSize = 20.0,
                                    useInterpolation = false,
                                )

                            if (contours.isNotEmpty()) {
                                customGradShader.parameter("timeX", loopX * 2.0 + (z * 0.007))
                                customGradShader.parameter("timeY", loopY * 2.0 + (z * 0.009))
                                customGradShader.parameter("colorA", ColorRGBa.RED * 1.2)
                                customGradShader.parameter("colorB", ColorRGBa.CYAN * 1.2)

                                drawer.shadeStyle = customGradShader

                                drawer.fill = ColorRGBa.WHITE.opacify(1.00)
                                drawer.stroke = ColorRGBa.WHITE.opacify(1.0)
                                drawer.strokeWeight = 5.0

                                val layerShape = Shape(contours)
                                drawer.shape(layerShape)
                            }
                        }
                    }
                }
            }
        }
    }
