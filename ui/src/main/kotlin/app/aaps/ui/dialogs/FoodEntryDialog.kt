package app.aaps.ui.dialogs

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogFoodEntryBinding
import app.aaps.ui.food.BarcodeLookupResult
import app.aaps.ui.food.CarbohydrateDefinition
import app.aaps.ui.food.FoodCatalog
import app.aaps.ui.food.FoodPreparation
import app.aaps.ui.food.FoodProduct
import app.aaps.ui.food.FoodSource
import app.aaps.ui.food.MealDescriptionComponent
import app.aaps.ui.food.MealDescriptionIssue
import app.aaps.ui.food.MealDescriptionParser
import app.aaps.ui.food.NutritionBasis
import app.aaps.ui.food.PortionUnit
import app.aaps.ui.food.recognition.LocalDishClassifier
import app.aaps.ui.food.recognition.LocalFoodRecognizer
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.android.support.DaggerDialogFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.text.DecimalFormat
import java.math.BigDecimal
import javax.inject.Inject

/** Builds a checked food draft. Only the existing wizard can record or deliver treatment. */
class FoodEntryDialog : DaggerDialogFragment() {

    @Inject lateinit var config: Config
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var loop: Loop
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var localFoodRecognizer: LocalFoodRecognizer
    @Inject lateinit var localDishClassifier: LocalDishClassifier

    private var _binding: DialogFoodEntryBinding? = null
    private val binding get() = _binding!!
    private lateinit var catalog: FoodCatalog
    private var selected: FoodProduct? = null
    private val portions = mutableListOf<CheckedFoodPortion>()
    private val pending = mutableListOf<MealDescriptionComponent>()
    private var mode = MODE_SEARCH
    private var barcode: String? = null
    private var labelSource: FoodProduct? = null
    private var work: Job? = null
    private var workGeneration = 0
    private var busy = false
    private var restoring = false
    private var handingOff = false
    private var draftVersion = 0
    private var confirmation: AlertDialog? = null
    private val spinnerSelections = mutableMapOf<Int, Int>()
    private val usesHealfiScenario: Boolean get() = config.FLAVOR == "healfi"
    private var methodsExpanded = false
    private var sourceExpanded = false

    // The URI and image are deliberately never written into saved state or app storage.
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && _binding != null) recognizePhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        methodsExpanded = savedInstanceState?.getBoolean(STATE_METHODS_OPEN) ?: false
        sourceExpanded = savedInstanceState?.getBoolean(STATE_SOURCE_OPEN) ?: false
        selected = savedInstanceState?.getBundle(STATE_SELECTED)?.let(::readProduct)
        labelSource = savedInstanceState?.getBundle(STATE_LABEL_SOURCE)?.let(::readProduct)
        barcode = savedInstanceState?.getString(STATE_BARCODE)?.takeIf { it.matches(Regex("[0-9]{8,14}")) }
        mode = savedInstanceState?.getInt(STATE_MODE, MODE_SEARCH) ?: MODE_SEARCH
        savedInstanceState?.getParcelableArrayList<Bundle>(STATE_PORTIONS)?.take(MAX_COMPONENTS)?.forEach { state ->
            readPortion(state)?.let(portions::add)
        }
        savedInstanceState?.getParcelableArrayList<Bundle>(STATE_PENDING)?.take(MAX_COMPONENTS)?.forEach { state ->
            pending.add(
                MealDescriptionComponent(
                    rawText = state.getString("raw", ""),
                    foodQuery = state.getString("query", ""),
                    amount = if (state.containsKey("amount")) state.getDouble("amount").takeIf { it.isFinite() && it > 0 } else null,
                    unit = enumValue<PortionUnit>(state.getString("unit")),
                    needsPortionCheck = state.getBoolean("check", true),
                    issue = enumValue<MealDescriptionIssue>(state.getString("issue"))
                )
            )
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog?.setCanceledOnTouchOutside(false)
        _binding = if (usesHealfiScenario) {
            DialogFoodEntryBinding.bind(inflater.inflate(R.layout.healfi_dialog_food_entry, container, false))
        } else DialogFoodEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoring = true
        busy = false
        handingOff = false
        spinnerSelections.clear()
        catalog = FoodCatalog(requireContext().applicationContext)
        setupSpinner(binding.portionUnit, PortionUnit.entries.map(::unitLabel)) { invalidatePortion() }
        setupSpinner(binding.labelBasis, NutritionBasis.entries.map(::basisLabel)) { invalidateLabel() }
        setupSpinner(binding.labelDefinition, CarbohydrateDefinition.entries.map(::definitionLabel)) { invalidateLabel() }
        setupSpinner(binding.labelPreparation, FoodPreparation.entries.map(::preparationLabel)) { invalidateLabel() }
        setSpinnerSelection(binding.labelBasis, NutritionBasis.UNKNOWN.ordinal)
        setSpinnerSelection(binding.labelDefinition, CarbohydrateDefinition.UNKNOWN.ordinal)
        setSpinnerSelection(binding.labelPreparation, FoodPreparation.AS_SOLD.ordinal)
        binding.amount.doAfterTextChanged { if (!restoring) invalidatePortion() }
        binding.labelName.doAfterTextChanged { if (!restoring) invalidateLabel() }
        binding.labelCarbs.doAfterTextChanged { if (!restoring) invalidateLabel() }
        binding.query.doAfterTextChanged { if (!restoring) invalidatePortion() }
        binding.sourceChecked.setOnCheckedChangeListener { _, _ ->
            if (!restoring) draftVersion++
            renderPreview()
        }
        binding.inputModes.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                mode = when (id) { R.id.mode_text -> MODE_TEXT; R.id.mode_photo -> MODE_PHOTO; else -> MODE_SEARCH }
                renderMode()
            }
        }
        binding.searchButton.setOnClickListener { searchFromInput() }
        binding.resetDescription.setOnClickListener { clearPendingDescription() }
        binding.discardSelection.setOnClickListener {
            if (!handingOff) { selected = null; draftVersion++; binding.sourceChecked.isChecked = false; renderSelected() }
        }
        binding.query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { searchFromInput(); true } else false
        }
        binding.photoButton.setOnClickListener { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        binding.labelToggle.setOnClickListener { binding.labelEditor.visibility = if (binding.labelEditor.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        binding.useLabelButton.setOnClickListener { useLabel() }
        binding.barcodeButton.setOnClickListener { lookupBarcode() }
        binding.addButton.setOnClickListener { addPortion() }
        binding.continueButton.setOnClickListener { if (usesHealfiScenario) continueWithCheckedPortion() else reviewMeal() }
        binding.manualButton.setOnClickListener { openProtectedCarbWizard() }
        binding.cancelButton.setOnClickListener { dismiss() }
        if (usesHealfiScenario) {
            binding.root.findViewById<View>(R.id.healfi_food_methods_toggle).setOnClickListener {
                methodsExpanded = !methodsExpanded
                renderHealfiDisclosure()
            }
            binding.root.findViewById<View>(R.id.healfi_food_source_toggle).setOnClickListener {
                sourceExpanded = !sourceExpanded
                renderHealfiDisclosure()
            }
        }
        renderMode()
        renderSelected()
        renderMeal()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        restoring = true
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.let { state ->
            binding.query.setText(state.getString(STATE_QUERY, ""))
            binding.amount.setText(state.getString(STATE_AMOUNT, ""))
            setSpinnerSelection(binding.portionUnit, state.getInt(STATE_UNIT).coerceIn(0, PortionUnit.entries.lastIndex))
            binding.labelName.setText(state.getString(STATE_LABEL_NAME, ""))
            binding.labelCarbs.setText(state.getString(STATE_LABEL_CARBS, ""))
            setSpinnerSelection(binding.labelBasis, state.getInt(STATE_LABEL_BASIS, NutritionBasis.UNKNOWN.ordinal).coerceIn(0, NutritionBasis.entries.lastIndex))
            setSpinnerSelection(binding.labelDefinition, state.getInt(STATE_LABEL_DEFINITION, CarbohydrateDefinition.UNKNOWN.ordinal).coerceIn(0, CarbohydrateDefinition.entries.lastIndex))
            setSpinnerSelection(binding.labelPreparation, state.getInt(STATE_LABEL_PREPARATION, FoodPreparation.UNKNOWN.ordinal).coerceIn(0, FoodPreparation.entries.lastIndex))
            binding.labelEditor.visibility = if (state.getBoolean(STATE_LABEL_OPEN)) View.VISIBLE else View.GONE
            binding.ocrText.text = state.getString(STATE_OCR_TEXT, "").take(4000)
            binding.sourceChecked.isChecked = state.getBoolean(STATE_CHECKED)
        }
        binding.inputModes.check(when (mode) { MODE_TEXT -> R.id.mode_text; MODE_PHOTO -> R.id.mode_photo; else -> R.id.mode_search })
        restoring = false
        binding.barcodeHint.visibility = if (barcode == null) View.GONE else View.VISIBLE
        binding.barcodeButton.visibility = if (barcode == null) View.GONE else View.VISIBLE
        renderSelected()
        renderMeal()
        if (savedInstanceState == null) searchCatalog("")
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.9).toInt())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selected?.let { outState.putBundle(STATE_SELECTED, writeProduct(it)) }
        labelSource?.let { outState.putBundle(STATE_LABEL_SOURCE, writeProduct(it)) }
        barcode?.let { outState.putString(STATE_BARCODE, it) }
        outState.putParcelableArrayList(STATE_PORTIONS, ArrayList(portions.map(::writePortion)))
        outState.putParcelableArrayList(STATE_PENDING, ArrayList(pending.map { component ->
            Bundle().apply {
                putString("raw", component.rawText); putString("query", component.foodQuery)
                component.amount?.let { putDouble("amount", it) }; component.unit?.let { putString("unit", it.name) }
                putBoolean("check", component.needsPortionCheck); component.issue?.let { putString("issue", it.name) }
            }
        }))
        outState.putInt(STATE_MODE, mode)
        outState.putBoolean(STATE_METHODS_OPEN, methodsExpanded)
        outState.putBoolean(STATE_SOURCE_OPEN, sourceExpanded)
        _binding?.let { view ->
            outState.putString(STATE_QUERY, view.query.text.toString())
            outState.putString(STATE_AMOUNT, view.amount.text.toString())
            outState.putInt(STATE_UNIT, view.portionUnit.selectedItemPosition)
            outState.putBoolean(STATE_CHECKED, view.sourceChecked.isChecked)
            outState.putString(STATE_LABEL_NAME, view.labelName.text.toString())
            outState.putString(STATE_LABEL_CARBS, view.labelCarbs.text.toString())
            outState.putString(STATE_OCR_TEXT, view.ocrText.text.toString().take(4000))
            outState.putInt(STATE_LABEL_BASIS, view.labelBasis.selectedItemPosition)
            outState.putInt(STATE_LABEL_DEFINITION, view.labelDefinition.selectedItemPosition)
            outState.putInt(STATE_LABEL_PREPARATION, view.labelPreparation.selectedItemPosition)
            outState.putBoolean(STATE_LABEL_OPEN, view.labelEditor.visibility == View.VISIBLE)
        }
    }

    override fun onDestroyView() {
        workGeneration++
        draftVersion++
        busy = false
        handingOff = false
        work?.cancel()
        confirmation?.dismiss()
        confirmation = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupSpinner(spinner: Spinner, labels: List<String>, changed: () -> Unit) {
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerSelections[spinner.id] = spinner.selectedItemPosition
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Android can deliver initial and restoration callbacks after the restoration method returns.
                if (position != spinner.selectedItemPosition) return
                val previous = spinnerSelections.put(spinner.id, position)
                if (!restoring && previous != null && previous != position) changed()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setSpinnerSelection(spinner: Spinner, position: Int) {
        spinnerSelections[spinner.id] = position
        spinner.setSelection(position)
    }

    private fun renderMode() {
        binding.photoButton.visibility = if (mode == MODE_PHOTO) View.VISIBLE else View.GONE
        binding.modeHint.setText(when {
            mode == MODE_PHOTO -> R.string.food_entry_photo_hint
            mode == MODE_TEXT -> R.string.food_entry_text_hint
            usesHealfiScenario -> R.string.healfi_food_search_hint
            else -> R.string.food_entry_search_hint
        })
        renderPending()
        renderHealfiDisclosure()
    }

    private fun renderPending() {
        binding.componentHint.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        binding.resetDescription.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        pending.firstOrNull()?.let {
            binding.componentHint.text = getString(R.string.food_entry_component, it.rawText, pending.size) +
                if (it.issue != null || it.needsPortionCheck) "\n" + getString(R.string.food_entry_portion_check) else ""
        }
    }

    private fun searchFromInput() {
        if (handingOff) return
        val query = binding.query.text.toString().trim()
        if (mode == MODE_TEXT && pending.isEmpty()) {
            val components = MealDescriptionParser.parse(query).components
            if (components.size + portions.size > MAX_COMPONENTS) { binding.status.setText(R.string.food_entry_too_many_components); return }
            pending.addAll(components)
            selected = null
            draftVersion++
            renderPending()
            renderSelected()
            pending.firstOrNull()?.let { component ->
                prefillPortion(component)
                searchCatalog(component.foodQuery)
                return
            }
        }
        searchCatalog(query)
    }

    private fun clearPendingDescription() {
        if (handingOff) return
        work?.cancel()
        workGeneration++
        busy = false
        binding.progress.visibility = View.GONE
        pending.clear()
        selected = null
        draftVersion++
        binding.sourceChecked.isChecked = false
        binding.status.setText(R.string.food_entry_description_reset)
        renderPending()
        renderSelected()
    }

    private fun searchCatalog(query: String) = startWork(R.string.food_entry_loading) {
        val results = if (query.isBlank()) emptyList() else catalog.search(query, SEARCH_LIMIT)
        val size = catalog.size()
        ensureActive()
        if (_binding == null) return@startWork
        binding.searchResults.visibility = View.VISIBLE
        binding.searchResults.removeAllViews()
        results.forEach { food ->
            binding.searchResults.addView(resultButton("${food.name}\n${preparationLabel(food.preparation)} · ${sourceLabel(food)}") { selectProduct(food) })
        }
        binding.status.text = when {
            query.isBlank() -> getString(R.string.food_entry_welcome, size)
            results.isEmpty() -> getString(R.string.food_entry_no_matches)
            else -> getString(R.string.food_entry_matches, results.size, size)
        }
        if (query.isNotBlank() && results.isEmpty()) binding.labelEditor.visibility = View.VISIBLE
    }

    private fun selectProduct(food: FoodProduct) {
        if (handingOff) return
        selected = food
        sourceExpanded = false
        if (usesHealfiScenario) binding.status.text = ""
        binding.searchResults.visibility = View.GONE
        binding.dishSuggestions.visibility = View.GONE
        binding.labelEditor.visibility = View.GONE
        draftVersion++
        binding.sourceChecked.isChecked = false
        if (pending.isNotEmpty()) prefillPortion(pending.first())
        else setSpinnerSelection(binding.portionUnit, if (food.basis == NutritionBasis.PER_100_MILLILITERS) PortionUnit.MILLILITERS.ordinal else PortionUnit.GRAMS.ordinal)
        renderSelected()
        if (food.source != FoodSource.USER_LABEL) labelSource = null
        if (food.basis == NutritionBasis.UNKNOWN || food.carbohydrateDefinition == CarbohydrateDefinition.UNKNOWN) {
            labelSource = food
            binding.labelEditor.visibility = View.VISIBLE
            binding.labelName.setText(food.name)
            binding.labelCarbs.setText(food.carbohydrateGrams?.let(::inputNumber) ?: "")
            setSpinnerSelection(binding.labelBasis, food.basis.ordinal)
            setSpinnerSelection(binding.labelDefinition, food.carbohydrateDefinition.ordinal)
            setSpinnerSelection(binding.labelPreparation, food.preparation.ordinal)
        }
        renderPreview()
        val view = binding
        view.foodScroll.post {
            if (_binding === view) view.foodScroll.smoothScrollTo(0, if (view.labelEditor.visibility == View.VISIBLE) view.labelEditor.top else view.selectedCard.top)
        }
    }

    private fun prefillPortion(component: MealDescriptionComponent) {
        binding.amount.setText(component.amount?.takeIf { it.isFinite() && it > 0 && it <= MAX_PORTION }?.let(::inputNumber) ?: "")
        component.unit?.let { setSpinnerSelection(binding.portionUnit, it.ordinal) }
    }

    private fun renderSelected() {
        binding.selectedCard.visibility = if (selected == null) View.GONE else View.VISIBLE
        selected?.let { food ->
            binding.foodName.text = food.name
            val sourceDetails = listOfNotNull(food.id, food.dataType, food.sourceUrl).joinToString("\n")
            if (usesHealfiScenario) {
                binding.foodSource.text = sourceLabel(food)
                binding.root.findViewById<TextView>(R.id.healfi_food_source_details).text = sourceDetails
            } else binding.foodSource.text = getString(R.string.food_entry_source, sourceLabel(food), sourceDetails)
            binding.foodPreparation.text = getString(R.string.food_entry_preparation, preparationLabel(food.preparation))
            binding.foodBasis.text = listOf(
                getString(R.string.food_entry_basis_note, basisLabel(food.basis), definitionLabel(food.carbohydrateDefinition)),
                *food.warnings.toTypedArray()
            ).filter { it.isNotBlank() }.joinToString("\n")
        }
        renderHealfiDisclosure()
        renderPreview()
    }

    /** Changes presentation only; the checked draft and the existing handoff remain authoritative. */
    private fun renderHealfiDisclosure() {
        if (!usesHealfiScenario || _binding == null) return
        val choosingFood = selected == null
        binding.root.findViewById<View>(R.id.healfi_food_find_panel).visibility = if (choosingFood) View.VISIBLE else View.GONE
        binding.root.findViewById<View>(R.id.healfi_food_methods_panel).visibility = if (methodsExpanded) View.VISIBLE else View.GONE
        binding.root.findViewById<MaterialButton>(R.id.healfi_food_methods_toggle).apply {
            setText(if (methodsExpanded) R.string.healfi_food_methods_hide else R.string.healfi_food_methods_show)
            isEnabled = !busy && !handingOff
        }
        binding.root.findViewById<MaterialButton>(R.id.healfi_food_source_toggle).apply {
            setText(if (sourceExpanded) R.string.healfi_food_source_hide else R.string.healfi_food_source_show)
            isEnabled = !handingOff
        }
        binding.root.findViewById<View>(R.id.healfi_food_source_details).visibility = if (sourceExpanded) View.VISIBLE else View.GONE
        binding.root.findViewById<TextView>(R.id.healfi_food_stage).setText(
            if (choosingFood) R.string.healfi_food_stage_find else R.string.healfi_food_stage_portion
        )
        binding.root.findViewById<View>(R.id.healfi_food_meal_panel).visibility = if (portions.isEmpty()) View.GONE else View.VISIBLE
    }

    /** One navigation tap can add a checked last portion and open the unchanged meal review. */
    private fun continueWithCheckedPortion() {
        if (busy || handingOff || confirmation?.isShowing == true) return
        if (selected != null) {
            if (!binding.addButton.isEnabled || pending.size > 1) return
            addPortion()
        }
        if (selected == null && pending.isEmpty()) reviewMeal()
    }

    private fun invalidatePortion() {
        if (restoring) return
        draftVersion++
        binding.sourceChecked.isChecked = false
        renderPreview()
    }

    private fun invalidateLabel() {
        if (restoring) return
        draftVersion++
        if (selected?.source == FoodSource.USER_LABEL) {
            selected = null
            binding.sourceChecked.isChecked = false
            renderSelected()
        }
    }

    private fun currentPortion(): CheckedFoodPortion? {
        val food = selected ?: return null
        if (food.carbohydrateDefinition == CarbohydrateDefinition.UNKNOWN) return null
        val amount = numeric(binding.amount.text.toString())?.takeIf { it > 0 && it <= MAX_PORTION } ?: return null
        val unit = PortionUnit.entries.getOrNull(binding.portionUnit.selectedItemPosition) ?: return null
        return CheckedFoodPortion(food, amount, unit).takeIf { it.carbohydrate()?.let { value -> value.isFinite() && value >= 0 } == true }
    }

    private fun totalCarbohydrate(items: List<CheckedFoodPortion> = portions): Double? = FoodEntryCalculation.subtotal(items)

    private fun renderPreview() {
        _binding ?: return
        val current = currentPortion()
        val mixedDefinitions = FoodEntryCalculation.hasMixedDefinitions(portions + listOfNotNull(current))
        binding.portionPreview.text = when {
            mixedDefinitions -> getString(R.string.food_entry_mixed_definitions)
            selected?.carbohydrateDefinition == CarbohydrateDefinition.UNKNOWN -> getString(R.string.food_entry_unknown_definition)
            current == null -> getString(R.string.food_entry_preview_missing)
            else -> getString(R.string.food_entry_carbs_preview, number(current.carbohydrate()!!))
        }
        val maximum = constraintChecker.getMaxCarbsAllowed().value().toDouble()
        val candidateTotal = current?.let { totalCarbohydrate(portions + it) }
        binding.addButton.isEnabled = !busy && !handingOff && binding.sourceChecked.isChecked && candidateTotal != null && candidateTotal <= maximum && portions.size < MAX_COMPONENTS
        val total = totalCarbohydrate()
        binding.mealTotal.text = when {
            pending.isNotEmpty() -> getString(R.string.food_entry_pending)
            FoodEntryCalculation.hasMixedDefinitions(portions) -> getString(R.string.food_entry_mixed_definitions)
            total == null -> getString(R.string.food_entry_meal_empty)
            total > maximum -> getString(R.string.food_entry_native_limit)
            else -> getString(R.string.food_entry_carbs_preview, number(total))
        }
        binding.continueButton.isEnabled = !busy && !handingOff && selected == null && pending.isEmpty() && total != null && total <= maximum
        if (usesHealfiScenario && selected != null) {
            binding.continueButton.isEnabled = binding.addButton.isEnabled && pending.size <= 1
            binding.mealTotal.text = when {
                pending.size > 1 -> getString(R.string.food_entry_pending)
                mixedDefinitions -> getString(R.string.food_entry_mixed_definitions)
                candidateTotal == null -> getString(R.string.healfi_food_waiting)
                candidateTotal > maximum -> getString(R.string.food_entry_native_limit)
                else -> getString(R.string.healfi_food_draft_total, number(candidateTotal))
            }
        } else if (usesHealfiScenario && portions.isEmpty() && pending.isEmpty()) {
            binding.mealTotal.setText(R.string.healfi_food_waiting)
        }
        binding.manualButton.isEnabled = !handingOff
        binding.useLabelButton.isEnabled = !busy && !handingOff
        binding.amount.isEnabled = !handingOff
        binding.portionUnit.isEnabled = !handingOff
        binding.sourceChecked.isEnabled = !busy && !handingOff
        binding.labelName.isEnabled = !busy && !handingOff
        binding.labelCarbs.isEnabled = !busy && !handingOff
        binding.labelBasis.isEnabled = !busy && !handingOff
        binding.labelDefinition.isEnabled = !busy && !handingOff
        binding.labelPreparation.isEnabled = !busy && !handingOff
        binding.resetDescription.isEnabled = !handingOff
        binding.discardSelection.isEnabled = !handingOff
        renderHealfiDisclosure()
    }

    private fun addPortion() {
        if (busy || handingOff || !binding.sourceChecked.isChecked || portions.size >= MAX_COMPONENTS) return
        val portion = currentPortion() ?: return
        val total = totalCarbohydrate(portions + portion) ?: return
        if (total > constraintChecker.getMaxCarbsAllowed().value()) { binding.status.setText(R.string.food_entry_native_limit); return }
        portions.add(portion)
        selected = null
        draftVersion++
        binding.sourceChecked.isChecked = false
        binding.amount.setText("")
        if (pending.isNotEmpty()) pending.removeAt(0)
        renderSelected()
        renderMeal()
        pending.firstOrNull()?.let { component ->
            binding.query.setText(component.foodQuery)
            prefillPortion(component)
            searchCatalog(component.foodQuery)
        }
    }

    private fun renderMeal() {
        binding.mealItems.removeAllViews()
        portions.forEachIndexed { index, portion ->
            if (usesHealfiScenario) {
                val row = layoutInflater.inflate(R.layout.healfi_food_entry_meal_item, binding.mealItems, false)
                row.findViewById<TextView>(R.id.healfi_meal_summary).text = getString(
                    R.string.healfi_food_meal_item, portion.product.name, number(portion.amount), unitLabel(portion.unit),
                    preparationLabel(portion.product.preparation), number(portion.carbohydrate()!!)
                )
                row.findViewById<MaterialButton>(R.id.healfi_meal_remove).apply {
                    contentDescription = getString(R.string.food_entry_remove_description, portion.product.name)
                    isEnabled = !handingOff
                    setOnClickListener {
                        if (handingOff) return@setOnClickListener
                        portions.removeAt(index)
                        draftVersion++
                        renderMeal()
                    }
                }
                binding.mealItems.addView(row)
                return@forEachIndexed
            }
            binding.mealItems.addView(TextView(requireContext()).apply { text = portionSummary(portion); setPadding(0, 12, 0, 0) })
            binding.mealItems.addView(resultButton(getString(R.string.food_entry_remove)) {
                if (handingOff) return@resultButton
                portions.removeAt(index)
                draftVersion++
                renderMeal()
            }.apply { contentDescription = getString(R.string.food_entry_remove_description, portion.product.name) })
        }
        renderPending()
        renderPreview()
    }

    private fun useLabel() {
        if (busy || handingOff) return
        val name = binding.labelName.text.toString().trim()
        val value = numeric(binding.labelCarbs.text.toString())
        val basis = NutritionBasis.entries.getOrNull(binding.labelBasis.selectedItemPosition) ?: NutritionBasis.UNKNOWN
        val definition = CarbohydrateDefinition.entries.getOrNull(binding.labelDefinition.selectedItemPosition) ?: CarbohydrateDefinition.UNKNOWN
        val preparation = FoodPreparation.entries.getOrNull(binding.labelPreparation.selectedItemPosition) ?: FoodPreparation.UNKNOWN
        if (name.isEmpty() || value == null || value < 0 || (basis == NutritionBasis.PER_100_GRAMS && value > 100) ||
            basis !in setOf(NutritionBasis.PER_100_GRAMS, NutritionBasis.PER_100_MILLILITERS) || definition == CarbohydrateDefinition.UNKNOWN) {
            binding.status.setText(R.string.food_entry_label_error)
            return
        }
        val origin = labelSource
        val sourceUrl = origin?.sourceUrl
        selectProduct(FoodProduct("label:${System.nanoTime()}", name, FoodSource.USER_LABEL, sourceUrl = sourceUrl, basis = basis, carbohydrateDefinition = definition,
            preparation = preparation, carbohydrateGrams = value, barcode = origin?.barcode ?: barcode,
            dataType = origin?.let { "${it.sourceLabel} · ${it.id}" }, warnings = origin?.warnings ?: emptyList()))
    }

    private fun recognizePhoto(uri: Uri) {
        if (handingOff) return
        selected = null
        barcode = null
        labelSource = null
        draftVersion++
        binding.sourceChecked.isChecked = false
        binding.searchResults.removeAllViews()
        binding.dishSuggestions.removeAllViews()
        binding.barcodeHint.visibility = View.GONE
        binding.barcodeButton.visibility = View.GONE
        binding.ocrText.text = ""
        binding.labelName.setText("")
        binding.labelCarbs.setText("")
        setSpinnerSelection(binding.labelBasis, NutritionBasis.UNKNOWN.ordinal)
        setSpinnerSelection(binding.labelDefinition, CarbohydrateDefinition.UNKNOWN.ordinal)
        renderSelected()
        startWork(R.string.food_entry_recognizing) {
            val labelTask = async { try { localFoodRecognizer.recognize(uri) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
            val dishTask = async { try { localDishClassifier.classify(uri) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
            val label = labelTask.await()
            val dishes = dishTask.await()
            ensureActive()
            if (_binding == null) return@startWork
            binding.dishSuggestions.removeAllViews()
            binding.dishSuggestions.visibility = View.VISIBLE
            if (dishes.isNotEmpty()) {
                binding.dishSuggestions.addView(TextView(requireContext()).apply { setText(R.string.food_entry_dish_suggestions) })
                dishes.forEach { dish -> binding.dishSuggestions.addView(resultButton(dish.displayName) { binding.query.setText(dish.id.replace('_', ' ')); searchCatalog(dish.id.replace('_', ' ')) }) }
            }
            barcode = label?.barcode
            binding.barcodeHint.visibility = if (barcode == null) View.GONE else View.VISIBLE
            binding.barcodeButton.visibility = if (barcode == null) View.GONE else View.VISIBLE
            label?.let {
                binding.ocrText.text = it.rawText.take(4000)
                binding.labelName.setText(it.nutrition.productName ?: it.rawText.lineSequence().firstOrNull { line -> line.isNotBlank() }?.take(200) ?: "")
                binding.labelCarbs.setText(it.nutrition.carbohydrateGramsPer100g?.let(::inputNumber) ?: "")
                setSpinnerSelection(binding.labelBasis, NutritionBasis.entries.firstOrNull { value -> value.name == it.nutrition.basis.name }?.ordinal ?: NutritionBasis.UNKNOWN.ordinal)
                setSpinnerSelection(binding.labelDefinition, when (it.nutrition.carbohydrateDefinition.name) { "TOTAL" -> CarbohydrateDefinition.US_TOTAL_INCLUDES_FIBER.ordinal; "AVAILABLE" -> CarbohydrateDefinition.EU_AVAILABLE.ordinal; else -> CarbohydrateDefinition.UNKNOWN.ordinal })
                setSpinnerSelection(binding.labelPreparation, FoodPreparation.AS_SOLD.ordinal)
                binding.labelEditor.visibility = View.VISIBLE
            }
            binding.status.text = if (label == null && dishes.isEmpty()) getString(R.string.food_entry_failure) else getString(R.string.food_entry_photo_done) + (barcode?.let { "\n" + getString(R.string.food_entry_barcode, it) } ?: "")
        }
    }

    private fun lookupBarcode() {
        val value = barcode ?: return
        startWork(R.string.food_entry_loading) {
            val result = catalog.findBarcode(value)
            ensureActive()
            if (_binding == null) return@startWork
            when (result) {
                is BarcodeLookupResult.Found -> { selectProduct(result.product); binding.status.text = getString(R.string.food_entry_barcode, value) }
                BarcodeLookupResult.NotFound -> binding.status.setText(R.string.food_entry_barcode_missing)
                is BarcodeLookupResult.Failure -> binding.status.setText(R.string.food_entry_failure)
            }
        }
    }

    private fun startWork(message: Int, action: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (handingOff || _binding == null) return
        work?.cancel()
        val generation = ++workGeneration
        busy = true
        binding.progress.visibility = View.VISIBLE
        binding.status.setText(message)
        renderPreview()
        work = viewLifecycleOwner.lifecycleScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (_binding != null && generation == workGeneration) binding.status.setText(R.string.food_entry_failure) }
            finally {
                if (_binding != null && generation == workGeneration) { busy = false; binding.progress.visibility = View.GONE; renderPreview() }
            }
        }
    }

    private fun reviewMeal() {
        if (handingOff || busy || pending.isNotEmpty() || selected != null || confirmation?.isShowing == true) return
        val snapshot = portions.toList()
        val input = FoodEntryCalculation.wizardInput(snapshot, pending.size, selected != null, constraintChecker.getMaxCarbsAllowed().value())
            ?: run { binding.status.setText(R.string.food_entry_native_limit); return }
        val total = input.carbohydrateGrams
        val rounded = input.roundedCarbs
        if (!nativeCarbsAllowed(rounded)) { binding.status.setText(R.string.food_entry_native_limit); return }
        val version = draftVersion
        val summary = getString(R.string.food_entry_confirm_meal, snapshot.joinToString("\n\n", transform = ::portionSummary), number(total), rounded)
        confirmation = MaterialAlertDialogBuilder(requireContext(), app.aaps.core.ui.R.style.DialogTheme)
            .setTitle(R.string.food_entry_confirm_title)
            .setMessage(summary)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.food_entry_continue) { _, _ ->
                if (version != draftVersion) { binding.status.setText(R.string.food_entry_changed); return@setPositiveButton }
                openProtectedWizard(snapshot, total, rounded, version)
            }
            .create().also { it.setOnDismissListener { confirmation = null }; it.show(); it.setCanceledOnTouchOutside(false) }
    }

    private fun nativeCarbsAllowed(carbs: Int): Boolean = carbs >= 0 && carbs <= constraintChecker.getMaxCarbsAllowed().value() &&
        constraintChecker.applyCarbsConstraints(ConstraintObject(carbs, aapsLogger)).value() == carbs

    private fun readyForWizard(): Boolean = profileFunction.getProfile() != null && activePlugin.activePump.isInitialized() &&
        !activePlugin.activePump.isSuspended() && loop.runningMode != RM.Mode.DISCONNECTED_PUMP

    private fun openProtectedWizard(snapshot: List<CheckedFoodPortion>, total: Double, carbs: Int, version: Int) {
        val host = activity ?: return
        val view = _binding ?: return
        if (!readyForWizard()) { binding.status.setText(R.string.food_entry_unavailable); return }
        handingOff = true
        renderPreview()
        var completed = false
        fun finishWithoutHandoff(message: Int? = null) {
            if (completed || _binding !== view) return
            completed = true
            handingOff = false
            message?.let { view.status.setText(it) }
            renderPreview()
        }
        protectionCheck.queryProtection(host, ProtectionCheck.Protection.BOLUS, Runnable {
            if (completed || _binding !== view) return@Runnable
            if (!isAdded || parentFragmentManager.isStateSaved) { finishWithoutHandoff(); return@Runnable }
            if (draftVersion != version || portions != snapshot) { finishWithoutHandoff(R.string.food_entry_changed); return@Runnable }
            if (!readyForWizard() || total > constraintChecker.getMaxCarbsAllowed().value() || !nativeCarbsAllowed(carbs)) {
                finishWithoutHandoff(R.string.food_entry_unavailable)
                return@Runnable
            }
            completed = true
            uiInteraction.runWizardDialog(parentFragmentManager, carbs, snapshot.joinToString("; ") { it.product.name }.take(200))
            dismiss()
        }, Runnable { finishWithoutHandoff() }, Runnable { finishWithoutHandoff() })
    }

    private fun openProtectedCarbWizard() {
        if (handingOff) return
        val host = activity ?: return
        val view = _binding ?: return
        if (!readyForWizard()) { binding.status.setText(R.string.food_entry_unavailable); return }
        work?.cancel()
        workGeneration++
        busy = false
        binding.progress.visibility = View.GONE
        handingOff = true
        renderPreview()
        var completed = false
        fun finishWithoutHandoff(message: Int? = null) {
            if (completed || _binding !== view) return
            completed = true
            handingOff = false
            message?.let { view.status.setText(it) }
            renderPreview()
        }
        protectionCheck.queryProtection(host, ProtectionCheck.Protection.BOLUS, Runnable {
            if (completed || _binding !== view) return@Runnable
            if (!isAdded || parentFragmentManager.isStateSaved) { finishWithoutHandoff(); return@Runnable }
            if (!readyForWizard()) { finishWithoutHandoff(R.string.food_entry_unavailable); return@Runnable }
            completed = true
            // Manual carbohydrate entry uses the same active-profile recommendation
            // as confirmed food. Do not prefill an insulin dose or record the draft.
            uiInteraction.runWizardDialog(parentFragmentManager)
            dismiss()
        }, Runnable { finishWithoutHandoff() }, Runnable { finishWithoutHandoff() })
    }

    private fun resultButton(label: String, action: () -> Unit): MaterialButton =
        (layoutInflater.inflate(if (usesHealfiScenario) R.layout.healfi_food_entry_result_button else R.layout.food_entry_result_button, binding.searchResults, false) as MaterialButton).apply {
            text = label
            setOnClickListener { if (!handingOff) action() }
        }

    private fun portionSummary(portion: CheckedFoodPortion): String = listOf(
        portion.product.name, preparationLabel(portion.product.preparation), "${number(portion.amount)} ${unitLabel(portion.unit)}",
        sourceLabel(portion.product) + " · " + portion.product.id, definitionLabel(portion.product.carbohydrateDefinition),
        getString(R.string.food_entry_carbs_preview, number(portion.carbohydrate()!!))
    ).joinToString("\n")

    private fun sourceLabel(food: FoodProduct): String = getString(when (food.source) {
        FoodSource.OPEN_FOOD_FACTS -> R.string.food_entry_source_off
        FoodSource.USER_LABEL -> R.string.food_entry_source_label
        else -> R.string.food_entry_source_usda
    })

    private fun unitLabel(unit: PortionUnit): String = getString(if (unit == PortionUnit.GRAMS) R.string.food_entry_grams else R.string.food_entry_milliliters)
    private fun basisLabel(basis: NutritionBasis): String = getString(when (basis) {
        NutritionBasis.PER_100_GRAMS -> R.string.food_entry_per_100g
        NutritionBasis.PER_100_MILLILITERS -> R.string.food_entry_per_100ml
        NutritionBasis.PER_SERVING -> R.string.food_entry_per_serving
        NutritionBasis.UNKNOWN -> R.string.food_entry_unknown
    })

    private fun definitionLabel(definition: CarbohydrateDefinition): String = getString(when (definition) {
        CarbohydrateDefinition.US_TOTAL_INCLUDES_FIBER -> R.string.food_entry_us_total
        CarbohydrateDefinition.EU_AVAILABLE -> R.string.food_entry_eu_available
        CarbohydrateDefinition.UNKNOWN -> R.string.food_entry_unknown
    })

    private fun preparationLabel(preparation: FoodPreparation): String = getString(when (preparation) {
        FoodPreparation.RAW -> R.string.food_entry_raw
        FoodPreparation.COOKED -> R.string.food_entry_cooked
        FoodPreparation.AS_SOLD -> R.string.food_entry_as_sold
        FoodPreparation.AS_PREPARED -> R.string.food_entry_as_prepared
        FoodPreparation.UNKNOWN -> R.string.food_entry_unknown
    })

    private fun numeric(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun number(value: Double): String = DecimalFormat("0.########").format(value)
    private fun inputNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun writePortion(portion: CheckedFoodPortion): Bundle = Bundle().apply { putBundle("food", writeProduct(portion.product)); putDouble("amount", portion.amount); putString("unit", portion.unit.name) }
    private fun readPortion(state: Bundle): CheckedFoodPortion? {
        val product = state.getBundle("food")?.let(::readProduct) ?: return null
        val unit = enumValue<PortionUnit>(state.getString("unit")) ?: return null
        return CheckedFoodPortion(product, state.getDouble("amount"), unit).takeIf { it.amount.isFinite() && it.amount > 0 && it.amount <= MAX_PORTION && product.carbohydrateDefinition != CarbohydrateDefinition.UNKNOWN && it.carbohydrate()?.isFinite() == true }
    }

    private fun writeProduct(food: FoodProduct): Bundle = Bundle().apply {
        putString("id", food.id); putString("name", food.name); putString("source", food.source.name); putString("url", food.sourceUrl)
        putString("type", food.dataType); putString("basis", food.basis.name); putString("definition", food.carbohydrateDefinition.name)
        putString("preparation", food.preparation.name); food.carbohydrateGrams?.let { putDouble("carbs", it) }
        food.fiberGrams?.let { putDouble("fiber", it) }; food.fatGrams?.let { putDouble("fat", it) }
        food.proteinGrams?.let { putDouble("protein", it) }; food.energyKcal?.let { putDouble("energy", it) }; putString("barcode", food.barcode); putStringArrayList("warnings", ArrayList(food.warnings))
    }

    private fun readProduct(state: Bundle): FoodProduct? {
        val source = enumValue<FoodSource>(state.getString("source")) ?: return null
        return FoodProduct(state.getString("id") ?: return null, state.getString("name") ?: return null, source, sourceUrl = state.getString("url"), dataType = state.getString("type"),
            basis = enumValue<NutritionBasis>(state.getString("basis")) ?: NutritionBasis.UNKNOWN,
            carbohydrateDefinition = enumValue<CarbohydrateDefinition>(state.getString("definition")) ?: CarbohydrateDefinition.UNKNOWN,
            preparation = enumValue<FoodPreparation>(state.getString("preparation")) ?: FoodPreparation.UNKNOWN,
            carbohydrateGrams = if (state.containsKey("carbs")) state.getDouble("carbs") else null,
            fiberGrams = if (state.containsKey("fiber")) state.getDouble("fiber") else null,
            fatGrams = if (state.containsKey("fat")) state.getDouble("fat") else null,
            proteinGrams = if (state.containsKey("protein")) state.getDouble("protein") else null,
            energyKcal = if (state.containsKey("energy")) state.getDouble("energy") else null, barcode = state.getString("barcode"), warnings = state.getStringArrayList("warnings") ?: emptyList())
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?): T? = enumValues<T>().firstOrNull { it.name == name }

    companion object {
        private const val MODE_SEARCH = 0
        private const val MODE_TEXT = 1
        private const val MODE_PHOTO = 2
        private const val SEARCH_LIMIT = 20
        private const val MAX_COMPONENTS = FoodEntryCalculation.MAX_COMPONENTS
        private const val MAX_PORTION = FoodEntryCalculation.MAX_PORTION
        private const val STATE_SELECTED = "food.selected"
        private const val STATE_LABEL_SOURCE = "food.label.source"
        private const val STATE_BARCODE = "food.barcode"
        private const val STATE_OCR_TEXT = "food.label.ocr"
        private const val STATE_PORTIONS = "food.portions"
        private const val STATE_PENDING = "food.pending"
        private const val STATE_MODE = "food.mode"
        private const val STATE_QUERY = "food.query"
        private const val STATE_AMOUNT = "food.amount"
        private const val STATE_UNIT = "food.unit"
        private const val STATE_CHECKED = "food.checked"
        private const val STATE_LABEL_NAME = "food.label.name"
        private const val STATE_LABEL_CARBS = "food.label.carbs"
        private const val STATE_LABEL_BASIS = "food.label.basis"
        private const val STATE_LABEL_DEFINITION = "food.label.definition"
        private const val STATE_LABEL_PREPARATION = "food.label.preparation"
        private const val STATE_LABEL_OPEN = "food.label.open"
        private const val STATE_METHODS_OPEN = "food.methods.open"
        private const val STATE_SOURCE_OPEN = "food.source.open"
    }
}
