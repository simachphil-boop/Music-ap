package app.vesper.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.account.AccountException
import app.vesper.player.data.account.Profile
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ConfirmDialog
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.components.SettingRow
import app.vesper.player.ui.components.TextInputDialog
import app.vesper.player.ui.components.VesperChip
import app.vesper.player.ui.components.VesperDialog
import app.vesper.player.ui.components.VesperTextField
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Аккаунт»: registration / sign-in and the cloud copy of the library. Each account has its own
 * playlists, favourites and history on the phone; signing in on another phone brings them there.
 */
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val bottom = LocalBottomInset.current
    val session by vm.account.session.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
            }
        }
        item {
            when {
                !vm.account.configured -> NotConfigured()
                session == null -> SignInForm()
                else -> SignedIn()
            }
        }
    }
}

@Composable
private fun NotConfigured() {
    val c = Vesper.colors
    Column(Modifier.padding(horizontal = 20.dp)) {
        OverlineText("Аккаунт", color = c.ornament)
        Text("Облако не подключено", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "В этой сборке не указан облачный проект, поэтому регистрация недоступна. Приложение работает как раньше — " +
                "всё хранится на этом телефоне. Аккаунты появятся в сборке, где подключён проект Firebase.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
        )
    }
}

@Composable
private fun SignInForm() {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    val guestData = remember { vm.account.guestHasData() }
    var takeData by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        error = null
        note = null
        if (register && password != password2) {
            error = "Пароли не совпадают"
            return
        }
        busy = true
        scope.launch {
            try {
                if (register) vm.account.register(email, password, name, takeData && guestData)
                else vm.account.signIn(email, password, takeData && guestData)
                done = if (register) "Аккаунт создан" else "Вы вошли"
                delay(900)
                Profile.restart(context)
            } catch (e: AccountException) {
                error = e.message
            } catch (e: Exception) {
                error = "Не получилось: ${e.message ?: "неизвестная ошибка"}"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OverlineText("Аккаунт Floya", color = c.ornament)
        Text(if (register) "Регистрация" else "Вход", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        Text(
            "Войдите на любом телефоне — и там будут ваши плейлисты, избранное, история и коллекция фильмов. " +
                "У каждого аккаунта всё своё: другой человек ваших плейлистов не увидит.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VesperChip("Вход", !register, onClick = { register = false; error = null })
            VesperChip("Регистрация", register, onClick = { register = true; error = null })
        }
        if (register) VesperTextField(name, { name = it }, "Имя (как вас показывать)")
        OutlinedField(email, { email = it.trim() }, "Почта", KeyboardType.Email)
        PasswordField(password, { password = it }, "Пароль (от 6 символов)")
        if (register) PasswordField(password2, { password2 = it }, "Повторите пароль")

        if (guestData) {
            Row(
                Modifier.fillMaxWidth().clip12().background(c.surfaceHigh).clickable { takeData = !takeData }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Взять с собой то, что сейчас на телефоне", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    Text(
                        if (takeData) "Ваши плейлисты, избранное и история перейдут в этот аккаунт. Без входа приложение будет пустым — чужие их не увидят."
                        else "Аккаунт начнётся с чистого листа, а текущие плейлисты останутся в режиме без входа.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(takeData, { takeData = it }, colors = switchColors())
            }
        }

        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.danger) }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.accentBright) }
        done?.let { Text("$it. Перезапускаю приложение…", style = MaterialTheme.typography.bodyMedium, color = c.accentBright) }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.accentBright, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (register) "Создаю аккаунт…" else "Вхожу…", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
        } else {
            PrimaryButton(if (register) "Зарегистрироваться" else "Войти", onClick = { submit() }, modifier = Modifier.fillMaxWidth())
        }
        if (!register) {
            Text(
                "Забыли пароль?",
                style = MaterialTheme.typography.bodyMedium,
                color = c.accentBright,
                modifier = Modifier.clickable(enabled = !busy) {
                    error = null
                    scope.launch {
                        try {
                            vm.account.sendPasswordReset(email)
                            note = "Письмо для смены пароля отправлено на $email"
                        } catch (e: AccountException) {
                            error = e.message
                        }
                    }
                }.padding(vertical = 6.dp),
            )
        }
        Text(
            "Что хранится в облаке (Firebase, Google): плейлисты, избранное, счётчики прослушиваний и история, " +
                "позиции в видео, перенесённые плейлисты и коллекция фильмов. Сами аудио- и видеофайлы и ключи ИИ " +
                "не загружаются. Пароль уходит только в Firebase и в приложении не хранится.",
            style = MaterialTheme.typography.labelSmall,
            color = c.textMuted,
        )
    }
}

@Composable
private fun SignedIn() {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by vm.account.session.collectAsStateWithLifecycle()
    val status by vm.cloud.status.collectAsStateWithLifecycle()
    val s = session ?: return
    var renaming by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        OverlineText("Аккаунт Floya", color = c.ornament)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).background(c.accent, CircleShape).border(1.dp, c.ornament, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (s.name.ifBlank { s.email }).take(1).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.onAccent,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name.ifBlank { "Без имени" }, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                Text(s.email, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip12().background(c.surfaceHigh).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                status.busy -> CircularProgressIndicator(Modifier.size(22.dp), color = c.accentBright, strokeWidth = 2.dp)
                status.error != null -> Icon(Icons.Rounded.CloudOff, null, tint = c.danger, modifier = Modifier.size(22.dp))
                else -> Icon(Icons.Rounded.CloudDone, null, tint = c.accentBright, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        status.busy -> "Синхронизация…"
                        status.error != null -> "Не синхронизировано"
                        status.lastSyncAt > 0 -> "Синхронизировано ${ago(status.lastSyncAt)}"
                        else -> "Ещё не синхронизировано"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                )
                (status.error ?: status.info)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.error != null) c.danger else c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            "Синхронизировать сейчас",
            onClick = {
                if (!status.busy) scope.launch {
                    try {
                        vm.cloud.sync(fromUser = true)?.let { vm.message(it) }
                    } catch (e: AccountException) {
                        vm.message(e.message ?: "Ошибка синхронизации")
                    }
                }
            },
            icon = Icons.Rounded.Sync,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Изменения сохраняются в облако сами: через минуту после правки и когда вы сворачиваете приложение. " +
                "На другом телефоне песни, которых там нет, будут играть онлайн.",
            style = MaterialTheme.typography.labelSmall,
            color = c.textMuted,
        )
        working?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.accentBright)
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingRow(title = "Изменить имя", subtitle = s.name.ifBlank { "Не указано" }, icon = Icons.Rounded.Edit, onClick = { renaming = true })
    SettingRow(title = "Сменить пароль", subtitle = "Новый пароль для входа", icon = Icons.Rounded.Key, onClick = { changingPassword = true })
    SettingRow(title = "Выйти из аккаунта", subtitle = "Приложение откроется без входа", icon = Icons.Rounded.Logout, onClick = { signingOut = true })
    SettingRow(title = "Удалить аккаунт", subtitle = "Навсегда удалить аккаунт и облачную копию", icon = Icons.Rounded.PersonOff, onClick = { deleting = true })

    if (renaming) {
        TextInputDialog("Имя", "Как вас показывать", s.name, "Сохранить", onDismiss = { renaming = false }) { n ->
            renaming = false
            scope.launch {
                try {
                    vm.account.rename(n)
                    vm.message("Имя сохранено")
                } catch (e: AccountException) {
                    vm.message(e.message ?: "Не удалось сохранить имя")
                }
            }
        }
    }
    if (changingPassword) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        VesperDialog(
            title = "Новый пароль",
            onDismiss = { changingPassword = false },
            confirmLabel = "Сохранить",
            confirmEnabled = p1.length >= 6 && p1 == p2,
            onConfirm = {
                changingPassword = false
                scope.launch {
                    try {
                        vm.account.changePassword(p1)
                        vm.message("Пароль изменён")
                    } catch (e: AccountException) {
                        vm.message(e.message ?: "Не удалось сменить пароль")
                    }
                }
            },
        ) {
            PasswordField(p1, { p1 = it }, "Новый пароль (от 6 символов)")
            PasswordField(p2, { p2 = it }, "Повторите пароль")
            if (p2.isNotEmpty() && p1 != p2) Text("Пароли не совпадают", color = c.danger, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (signingOut) {
        var wipe by remember { mutableStateOf(true) }
        VesperDialog(
            title = "Выйти из аккаунта?",
            onDismiss = { signingOut = false },
            confirmLabel = "Выйти",
            destructive = true,
            onConfirm = {
                signingOut = false
                working = "Сохраняю изменения в облако…"
                scope.launch {
                    // Last changes go to the cloud first, so nothing is lost when the phone copy is removed.
                    val saved = runCatching { vm.cloud.sync(fromUser = true) }.isSuccess
                    vm.account.signOut(wipe = wipe && saved)
                    Profile.restart(context)
                }
            },
        ) {
            Text(
                "Ваши плейлисты останутся в облаке — войдите снова на этом или другом телефоне, и они вернутся.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )
            Row(Modifier.fillMaxWidth().clickable { wipe = !wipe }, verticalAlignment = Alignment.CenterVertically) {
                Text("Убрать мою копию с этого телефона", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Switch(wipe, { wipe = it }, colors = switchColors())
            }
            if (status.error != null) {
                Text("Сейчас нет связи с облаком — копия на телефоне будет оставлена, чтобы ничего не пропало.", style = MaterialTheme.typography.bodySmall, color = c.danger)
            }
        }
    }
    if (deleting) {
        ConfirmDialog(
            title = "Удалить аккаунт?",
            message = "Аккаунт и облачная копия плейлистов, избранного и истории будут удалены навсегда. Музыкальные файлы на телефоне не трогаются.",
            confirmLabel = "Удалить навсегда",
            onDismiss = { deleting = false },
        ) {
            deleting = false
            working = "Удаляю аккаунт…"
            scope.launch {
                try {
                    vm.account.deleteAccount(vm.cloud)
                    Profile.restart(context)
                } catch (e: AccountException) {
                    working = null
                    vm.message(e.message ?: "Не удалось удалить аккаунт")
                }
            }
        }
    }
}

@Composable
private fun OutlinedField(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType) {
    val c = Vesper.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = Vesper.shapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = Vesper.shapes.small,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            PressableIcon(
                if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                if (visible) "Скрыть пароль" else "Показать пароль",
                onClick = { visible = !visible },
            )
        },
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun fieldColors() = Vesper.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentBright,
        unfocusedBorderColor = c.outline,
        focusedLabelColor = c.accentBright,
        unfocusedLabelColor = c.textMuted,
        cursorColor = c.accentBright,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary,
    )
}

@Composable
private fun switchColors() = Vesper.colors.let { c ->
    SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.outline, uncheckedThumbColor = c.textMuted)
}

private fun Modifier.clip12() = this.clip(RoundedCornerShape(12.dp))

private fun ago(t: Long): String {
    val d = System.currentTimeMillis() - t
    return when {
        d < 60_000 -> "только что"
        d < 3_600_000 -> "${d / 60_000} мин назад"
        d < 86_400_000 -> "${d / 3_600_000} ч назад"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(t))
    }
}
