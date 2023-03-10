package io.horizontalsystems.bankwallet.modules.managewallets

import io.horizontalsystems.bankwallet.core.*
import io.horizontalsystems.bankwallet.core.managers.EvmTestnetManager
import io.horizontalsystems.bankwallet.core.managers.MarketKitWrapper
import io.horizontalsystems.bankwallet.core.managers.RestoreSettings
import io.horizontalsystems.bankwallet.core.managers.RestoreSettingsManager
import io.horizontalsystems.bankwallet.entities.Account
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.entities.ConfiguredToken
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.modules.enablecoin.EnableCoinService
import io.horizontalsystems.ethereumkit.core.AddressValidator
import io.horizontalsystems.marketkit.models.*
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject

class ManageWalletsService(
    private val marketKit: MarketKitWrapper,
    private val walletManager: IWalletManager,
    accountManager: IAccountManager,
    private val enableCoinService: EnableCoinService,
    private val restoreSettingsManager: RestoreSettingsManager,
    private val evmTestnetManager: EvmTestnetManager,
) : Clearable {

    val itemsObservable = PublishSubject.create<List<Item>>()
    var items: List<Item> = listOf()
        private set(value) {
            field = value
            itemsObservable.onNext(value)
        }

    val accountType: AccountType?
        get() = account?.type

    private val account: Account? = accountManager.activeAccount
    private var wallets = setOf<Wallet>()
    private var fullCoins = listOf<FullCoin>()

    private val disposables = CompositeDisposable()

    private var filter: String = ""

    init {
        walletManager.activeWalletsUpdatedObservable
            .subscribeIO {
                handleUpdated(it)
            }
            .let {
                disposables.add(it)
            }

        enableCoinService.enableCoinObservable
            .subscribeIO { (configuredPlatformCoins, settings) ->
                handleEnableCoin(configuredPlatformCoins, settings)
            }.let {
                disposables.add(it)
            }

        sync(walletManager.activeWallets)
        syncFullCoins()
        sortFullCoins()
        syncState()
    }

    private fun isEnabled(coin: Coin): Boolean {
        return wallets.any { it.token.coin == coin }
    }

    private fun sync(walletList: List<Wallet>) {
        wallets = walletList.toSet()
    }

    private fun fetchFullCoins(): List<FullCoin> {
        return if (filter.isBlank()) {
            val account = this.account ?: return emptyList()
            val testnetFullCoins = evmTestnetManager.nativeTokens(filter).map { it.fullCoin }
            val featuredFullCoins = marketKit.fullCoins("", 100).toMutableList()
                .filter { it.eligibleTokens(account.type).isNotEmpty() }

            val featuredCoins = featuredFullCoins.map { it.coin }
            val enabledFullCoins = marketKit.fullCoins(
                coinUids = wallets.filter { !featuredCoins.contains(it.coin) }.map { it.coin.uid }
            )
            val customFullCoins = wallets.filter { it.token.isCustom }.map { it.token.fullCoin }

            featuredFullCoins + enabledFullCoins + customFullCoins + testnetFullCoins
        } else if (isContractAddress(filter)) {
            val tokens = marketKit.tokens(filter) + evmTestnetManager.nativeTokens(filter)
            val coinUids = tokens.map { it.coin.uid }

            marketKit.fullCoins(coinUids).toMutableList()
        } else {
            marketKit.fullCoins(filter, 20).toMutableList() + evmTestnetManager.nativeTokens(filter).map { it.fullCoin }
        }
    }

    private fun isContractAddress(filter: String) = try {
        AddressValidator.validate(filter)
        true
    } catch (e: AddressValidator.AddressValidationException) {
        false
    }

    private fun syncFullCoins() {
        fullCoins = fetchFullCoins()
    }

    private fun sortFullCoins() {
        fullCoins = fullCoins.sortedByFilter(filter) {
            isEnabled(it.coin)
        }
    }

    private fun item(fullCoin: FullCoin): Item? {
        if (fullCoin.tokens.isNotEmpty() &&
            fullCoin.tokens.all { it.blockchain.type is BlockchainType.Unsupported }
        ) {
            return null
        }

        val accountType = account?.type ?: return null

        val eligibleTokens = fullCoin.eligibleTokens(accountType)
        if (eligibleTokens.isEmpty()) {
            return null
        }

        val enabled = isEnabled(fullCoin.coin)
        return Item(
            fullCoin = FullCoin(fullCoin.coin, eligibleTokens),
            enabled = enabled,
            hasSettings = enabled && hasSettingsOrPlatforms(eligibleTokens),
            hasInfo = enabled && fullCoin.tokens.firstOrNull()?.blockchainType == BlockchainType.Zcash
        )
    }

    private fun hasSettingsOrPlatforms(tokens: List<Token>): Boolean {
        return if (tokens.size == 1) {
            val token = tokens[0]
            token.blockchainType.coinSettingType != null || token.type !is TokenType.Native
        } else {
            true
        }
    }

    private fun syncState() {
        items = fullCoins.mapNotNull { item(it) }
    }

    private fun handleUpdated(wallets: List<Wallet>) {
        sync(wallets)

        val newFullCons = fetchFullCoins()
        if (newFullCons.size > fullCoins.size) {
            fullCoins = newFullCons
            sortFullCoins()
        }

        syncState()
    }

    private fun handleEnableCoin(
        configuredTokens: List<ConfiguredToken>, restoreSettings: RestoreSettings
    ) {
        val account = this.account ?: return
        val coin = configuredTokens.firstOrNull()?.token?.coin ?: return

        if (restoreSettings.isNotEmpty() && configuredTokens.size == 1) {
            enableCoinService.save(restoreSettings, account, configuredTokens.first().token.blockchainType)
        }

        val existingWallets = wallets.filter { it.coin == coin }
        val existingConfiguredPlatformCoins = existingWallets.map { it.configuredToken }
        val newConfiguredPlatformCoins = configuredTokens.minus(existingConfiguredPlatformCoins)

        val removedWallets = existingWallets.filter { !configuredTokens.contains(it.configuredToken) }
        val newWallets = newConfiguredPlatformCoins.map { Wallet(it, account) }

        if (newWallets.isNotEmpty() || removedWallets.isNotEmpty()) {
            walletManager.handle(newWallets, removedWallets)
        }
    }

    fun setFilter(filter: String) {
        this.filter = filter

        syncFullCoins()
        sortFullCoins()
        syncState()
    }

    fun enable(uid: String) {
        val fullCoin = fullCoins.firstOrNull { it.coin.uid == uid } ?: return
        enable(fullCoin)
    }

    fun enable(fullCoin: FullCoin) {
        val account = this.account ?: return
        enableCoinService.enable(fullCoin, account.type, account)
    }

    fun disable(uid: String) {
        val walletsToDelete = wallets.filter { it.coin.uid == uid }
        walletManager.delete(walletsToDelete)
    }

    fun configure(uid: String) {
        val account = this.account ?: return
        val fullCoin = fullCoins.firstOrNull { it.coin.uid == uid } ?: return
        val coinWallets = wallets.filter { it.coin == fullCoin.coin }
        enableCoinService.configure(fullCoin, account.type, coinWallets.map { it.configuredToken })
    }

    fun birthdayHeight(uid: String): Pair<Blockchain, Long>? {
        val token = fullCoins.firstOrNull { it.coin.uid == uid }?.tokens?.firstOrNull() ?: return null
        val account = this.account ?: return null
        val settings = restoreSettingsManager.settings(account, token.blockchainType)

        return settings.birthdayHeight?.let {
            Pair(token.blockchain, it)
        }
    }

    override fun clear() {
        disposables.clear()
    }

    data class Item(
        val fullCoin: FullCoin,
        val enabled: Boolean,
        val hasSettings: Boolean,
        val hasInfo: Boolean
    )
}
