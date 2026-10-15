package com.character.app.characters.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.character.app.characters.PlazaCharacter

/**
 * PlazaCharacterCard
 *
 * 2-column grid character card for PlazaScreen.
 */
@Composable
fun PlazaCharacterCard(
    character: PlazaCharacter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF14151B))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        // Cover Image (195dp height matching H5)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(195.dp)
        ) {
            Image(
                painter = painterResource(character.bgResId),
                contentDescription = character.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Bottom gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1.0f to Color(0xF214151B)
                        )
                    )
            )
            // Top-left category tag
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = character.badgeCategory,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(character.badgeColorHex)
                )
            }
            // Top-right vertical "由AI生成" badge
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x80000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "由AI生成",
                    fontSize = 9.sp,
                    color = Color(0xB3FFFFFF)
                )
            }
        }

        // Card Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Name row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = character.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "· ${character.subtitle}",
                    fontSize = 11.5.sp,
                    color = Color(0x99FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = character.gender,
                    fontSize = 11.sp,
                    color = if (character.gender == "♂") Color(0xFF69C0FF) else Color(0xFFFF85C0)
                )
            }

            // Quote
            Text(
                text = character.quote,
                fontSize = 11.5.sp,
                color = Color(0x80FFFFFF),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )

            Spacer(Modifier.height(2.dp))

            // Footer: views + author
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("👁️", fontSize = 9.sp)
                    Text(
                        text = character.likes,
                        fontSize = 10.sp,
                        color = Color(0x80FFFFFF)
                    )
                }
                Text(
                    text = character.author,
                    fontSize = 10.sp,
                    color = Color(0x66FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
