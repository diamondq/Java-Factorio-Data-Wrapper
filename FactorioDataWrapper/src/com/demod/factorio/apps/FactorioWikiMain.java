package com.demod.factorio.apps;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.json.JSONException;
import org.json.JSONObject;

import com.demod.factorio.DataTable;
import com.demod.factorio.FactorioData;
import com.demod.factorio.ModInfo;
import com.demod.factorio.Utils;
import com.demod.factorio.prototype.RecipePrototype;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;

public class FactorioWikiMain {

	public static void main(String[] args) throws JSONException, IOException {
		DataTable table = FactorioData.getTable();
		ModInfo baseInfo = new ModInfo(
				Utils.readJsonFromStream(new FileInputStream(new File(FactorioData.factorio, "data/base/info.json"))));

		File outputFolder = new File("output/" + baseInfo.getVersion());
		outputFolder.mkdirs();

		JSONObject wikiNaming = Utils
				.readJsonFromStream(FactorioWikiMain.class.getClassLoader().getResourceAsStream("wiki-naming.json"));
		JSONObject nameMappingTechnologies = wikiNaming.getJSONObject("technologies");
		JSONObject nameMappingItemsRecipes = wikiNaming.getJSONObject("items and recipes");

		try (PrintWriter pw = new PrintWriter(
				new File(outputFolder, "wiki-technologies-" + baseInfo.getVersion() + ".txt"))) {
			wiki_Technologies(table, nameMappingTechnologies, nameMappingItemsRecipes, pw);
		}

		try (PrintWriter pw = new PrintWriter(
				new File(outputFolder, "wiki-recipes-totals-" + baseInfo.getVersion() + ".txt"))) {
			wiki_RawTotals(table, nameMappingItemsRecipes, pw);
		}

		try (PrintWriter pw = new PrintWriter(new File(outputFolder, "wiki-items-" + baseInfo.getVersion() + ".txt"))) {
			wiki_Items(table, nameMappingTechnologies, nameMappingItemsRecipes, pw);
		}

		// try (PrintWriter pw = new PrintWriter(
		// new File(outputFolder, "wiki-default-naming-" + baseInfo.getVersion()
		// + ".json"))) {
		// wiki_DefaultNameMapping(table, pw);
		// }

		wiki_GenerateTintedIcons(table, new File(outputFolder, "icons"));

		Desktop.getDesktop().open(outputFolder);
	}

	@SuppressWarnings("unused")
	private static void wiki_DefaultNameMapping(DataTable table, PrintWriter pw) {
		JSONObject root = new JSONObject();
		root.put("items and recipes",
				wiki_DefaultNameMapping_NameGroup(Sets.union(table.getItems().keySet(), table.getRecipes().keySet())));
		root.put("technologies", wiki_DefaultNameMapping_NameGroup(table.getTechnologies().keySet()));
		pw.println(root);
	}

	private static JSONObject wiki_DefaultNameMapping_NameGroup(Set<String> names) {
		JSONObject ret = new JSONObject();
		Utils.terribleHackToHaveOrderedJSONObject(ret);

		names.stream().sorted().forEach(name -> {
			ret.put(name, wiki_fmtDefaultName(name));
		});
		return ret;
	}

	/**
	 * "ingredient names are done with spaces in between and Upper lower lower
	 * ... except for uranium" - Bilka
	 */
	public static String wiki_fmtDefaultName(String name) {
		String[] split = name.split("-");
		String formatted = Character.toUpperCase(split[0].charAt(0)) + split[0].substring(1);
		if (formatted.equals("Uranium") && split.length == 2 && split[1].startsWith("2")) {
			return formatted + "-" + split[1];
		}
		for (int i = 1; i < split.length; i++) {
			formatted += " " + split[i];
		}
		return formatted;
	}

	public static String wiki_fmtDouble(double value) {
		if (value == (long) value) {
			return String.format("%d", (long) value);
		} else {
			return Double.toString(value);// String.format("%f", value);
		}
	}

	/**
	 * Same as {@link #wiki_fmtName(String, JSONObject)}, but adds a ",
	 * [number]" when there is a number as the last part of the name. This adds
	 * the number to the icon.
	 */
	public static String wiki_fmtIconName(String name, JSONObject nameMappingJson) {
		String ret = wiki_fmtName(name, nameMappingJson);
		String[] split = ret.split("\\s+");
		Integer num = Ints.tryParse(split[split.length - 1]);
		if (num != null) {
			ret += ", " + num;
		}
		return ret;
	}

	public static String wiki_fmtName(String name, JSONObject nameMappingJson) {
		String ret = nameMappingJson.optString(name, null);
		if (ret == null) {
			System.err.println("\"" + name + "\":\"" + wiki_fmtDefaultName(name) + "\",");
			nameMappingJson.put(name, ret = wiki_fmtDefaultName(name));
		}
		return ret;
	}

	private static void wiki_GenerateTintedIcons(DataTable table, File folder) {
		folder.mkdirs();

		table.getRecipes().values().stream().forEach(recipe -> {
			if (!recipe.lua().get("icons").isnil()) {
				System.out.println();
				System.out.println(recipe.getName());
				Utils.debugPrintTable(recipe.lua().get("icons"));
				try {
					ImageIO.write(FactorioData.getIcon(recipe), "PNG", new File(folder, recipe.getName() + ".png"));
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
		table.getItems().values().stream().forEach(item -> {
			if (!item.lua().get("icons").isnil()) {
				System.out.println();
				System.out.println(item.getName());
				Utils.debugPrintTable(item.lua().get("icons"));
				try {
					ImageIO.write(FactorioData.getIcon(item), "PNG", new File(folder, item.getName() + ".png"));
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
	}

	private static void wiki_Items(DataTable table, JSONObject nameMappingTechnologies,
			JSONObject nameMappingItemsRecipes, PrintWriter pw) {
		Multimap<String, String> requiredTechnologies = LinkedHashMultimap.create();
		table.getTechnologies().values()
				.forEach(tech -> tech.getRecipeUnlocks().stream().map(table.getRecipes()::get)
						.flatMap(r -> r.getOutputs().keySet().stream())
						.forEach(name -> requiredTechnologies.put(name, tech.getName())));

		table.getItems().values().stream().sorted((i1, i2) -> i1.getName().compareTo(i2.getName())).forEach(item -> {
			pw.println(wiki_fmtName(item.getName(), nameMappingItemsRecipes));

			List<String> names = table.getRecipes().values().stream()
					.filter(r -> r.getInputs().containsKey(item.getName())).map(RecipePrototype::getName).sorted()
					.collect(Collectors.toList());
			if (!names.isEmpty()) {
				pw.println("|consumers = " + names.stream().map(n -> wiki_fmtName(n, nameMappingItemsRecipes))
						.collect(Collectors.joining(" + ")));
			}

			pw.println("|stack-size = " + item.lua().get("stack_size").toint());

			Collection<String> reqTech = requiredTechnologies.get(item.getName());
			if (!reqTech.isEmpty()) {
				pw.println("|required-technologies = " + reqTech.stream().sorted()
						.map(n -> wiki_fmtIconName(n, nameMappingTechnologies)).collect(Collectors.joining(" + ")));
			}

			pw.println();
		});
	}

	/**
	 * 
	 * List all hand-craftable recipes.
	 * 
	 * <pre>
	 * |recipe = Time, [ENERGY] + [Ingredient Name], [Ingredient Count] + ...  = [Ingredient Name], [Ingredient Count] + ...
	 * </pre>
	 * 
	 * If there is only one output ingredient with just 1 count, do not include
	 * the = part
	 * 
	 * <pre>
	 * |total-raw = Time, [ENERGY] + [Ingredient Name], [Ingredient Count] + ...
	 * </pre>
	 * 
	 * @param table
	 * @param mappingJson
	 */
	private static void wiki_RawTotals(DataTable table, JSONObject nameMappingJson, PrintWriter pw)
			throws FileNotFoundException {

		class TotalRawCalculator {
			Map<String, RecipePrototype> recipes;
			Map<String, Map<String, Double>> recipeTotalRaws = new LinkedHashMap<>();

			public Map<String, Double> compute(RecipePrototype recipe) {

				// if (recipeTotalRaws.containsKey(recipe.getName())) {
				// return recipeTotalRaws.get(recipe.getName());
				// }

				Map<String, Double> totalRaw = new LinkedHashMap<>();
				recipeTotalRaws.put(recipe.getName(), totalRaw);
				totalRaw.put(FactorioData.RAW_TIME, recipe.getEnergyRequired());

				for (Entry<String, Integer> entry : recipe.getInputs().entrySet()) {
					String input = entry.getKey();
					Optional<RecipePrototype> findRecipe = recipes.values().stream()
							.filter(r -> r.getOutputs().keySet().stream().anyMatch(i -> {
								return i.equals(input);
							})).findFirst();
					if (findRecipe.filter(RecipePrototype::isHandCraftable).isPresent()) {
						RecipePrototype inputRecipe = findRecipe.get();
						Map<String, Double> inputTotalRaw = compute(inputRecipe);
						Integer inputRunYield = inputRecipe.getOutputs().get(input);
						double inputRunCount = entry.getValue() / (double) inputRunYield;
						inputTotalRaw.forEach((k, v) -> {
							totalRaw.put(k, totalRaw.getOrDefault(k, 0.0) + v * inputRunCount);
						});
					} else {
						totalRaw.put(input, totalRaw.getOrDefault(input, 0.0) + entry.getValue());
					}
				}

				// System.out.println(recipe);
				// totalRaw.forEach((k, v) -> {
				// System.out.println("\tRAW " + k + " " + v);
				// });
				// System.out.println();
				// System.out.println();

				return totalRaw;
			}
		}

		Map<String, RecipePrototype> normalRecipes = table.getRecipes();
		Map<String, RecipePrototype> expensiveRecipes = table.getExpensiveRecipes();

		TotalRawCalculator normalTotalRawCalculator = new TotalRawCalculator();
		normalTotalRawCalculator.recipes = normalRecipes;
		TotalRawCalculator expensiveTotalRawCalculator = new TotalRawCalculator();
		expensiveTotalRawCalculator.recipes = expensiveRecipes;

		Sets.union(normalRecipes.keySet(), expensiveRecipes.keySet()).stream().sorted().forEach(name -> {
			pw.println(wiki_fmtName(name, nameMappingJson));

			{
				RecipePrototype recipe = normalRecipes.get(name);
				if (recipe != null) {
					pw.print("|recipe = ");
					pw.printf("Time, %s", wiki_fmtDouble(recipe.getEnergyRequired()));
					recipe.getInputs().entrySet().stream().sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
							.forEach(entry -> {
								pw.printf(" + %s, %d", wiki_fmtName(entry.getKey(), nameMappingJson), entry.getValue());
							});
					if (recipe.getOutputs().size() > 1
							|| recipe.getOutputs().values().stream().findFirst().get() != 1) {
						pw.print(" = ");
						pw.print(
								recipe.getOutputs().entrySet().stream()
										.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
										.map(entry -> String.format("%s, %d",
												wiki_fmtName(entry.getKey(), nameMappingJson), entry.getValue()))
										.collect(Collectors.joining(" + ")));
					}
					pw.println();

					Map<String, Double> totalRaw = normalTotalRawCalculator.compute(recipe);

					pw.print("|total-raw = ");
					pw.printf("Time, %s", wiki_fmtDouble(totalRaw.get(FactorioData.RAW_TIME)));
					totalRaw.entrySet().stream().filter(e -> !e.getKey().equals(FactorioData.RAW_TIME))
							.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey())).forEach(entry -> {
								pw.printf(" + %s, %s", wiki_fmtName(entry.getKey(), nameMappingJson),
										wiki_fmtDouble(entry.getValue()));
							});
					pw.println();
				}
			}
			{
				RecipePrototype recipe = expensiveRecipes.get(name);
				if (recipe != null) {
					pw.print("|expensive-recipe = ");
					pw.printf("Time, %s", wiki_fmtDouble(recipe.getEnergyRequired()));
					recipe.getInputs().entrySet().stream().sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
							.forEach(entry -> {
								pw.printf(" + %s, %d", wiki_fmtName(entry.getKey(), nameMappingJson), entry.getValue());
							});
					if (recipe.getOutputs().size() > 1
							|| recipe.getOutputs().values().stream().findFirst().get() != 1) {
						pw.print(" = ");
						pw.print(
								recipe.getOutputs().entrySet().stream()
										.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
										.map(entry -> String.format("%s, %d",
												wiki_fmtName(entry.getKey(), nameMappingJson), entry.getValue()))
										.collect(Collectors.joining(" + ")));
					}
					pw.println();

					Map<String, Double> totalRaw = expensiveTotalRawCalculator.compute(recipe);

					pw.print("|expensive-total-raw = ");
					pw.printf("Time, %s", wiki_fmtDouble(totalRaw.get(FactorioData.RAW_TIME)));
					totalRaw.entrySet().stream().filter(e -> !e.getKey().equals(FactorioData.RAW_TIME))
							.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey())).forEach(entry -> {
								pw.printf(" + %s, %s", wiki_fmtName(entry.getKey(), nameMappingJson),
										wiki_fmtDouble(entry.getValue()));
							});
					pw.println();
				}
			}

			pw.println();
		});
	}

	/**
	 * | cost = Time,30 + Science pack 1,1 + Science pack 2,1 + Science pack
	 * 3,1<br>
	 * |cost-multiplier = 1000 <br>
	 * |expensive-cost-multiplier = 4000<br>
	 * |required-technologies = Advanced electronics + Concrete <br>
	 * |allows = Atomic bomb + Uranium ammo + Kovarex enrichment process +
	 * Nuclear fuel reprocessing <br>
	 * |effects = Nuclear reactor + Centrifuge + Uranium processing + Uranium
	 * fuel cell + Heat exchanger + Heat pipe + Steam turbine <br>
	 * <br>
	 * allows are the techs it unlocks, effects are the items it unlocks. <br>
	 * bonuses are handled weirdly, we do one infobox per kind of bonus that
	 * gives the required technologies for the first tier of the bonus, no
	 * effect and the other bonus research as the allows, like this: <br>
	 * | cost = time, 60 + science pack 1,1 + science pack 2,1 + science pack
	 * 3,1 + military science pack,1 <br>
	 * | cost-multiplier = 100 <br>
	 * | required-technologies = tanks <br>
	 * | allows = Cannon shell damage (research), 2-5<br>
	 * <br>
	 * - Bilka
	 */
	private static void wiki_Technologies(DataTable table, JSONObject nameMappingTechnologies,
			JSONObject nameMappingItemsRecipes, PrintWriter pw) {
		Multimap<String, String> allowsMap = LinkedHashMultimap.create();
		table.getTechnologies().values().forEach(tech -> tech.getPrerequisites()
				.forEach(n -> allowsMap.put(n, tech.isBonus() ? tech.getBonusName() : tech.getName())));

		table.getTechnologies().values().stream().sorted((t1, t2) -> t1.getName().compareTo(t2.getName()))
				.filter(t -> !t.isBonus() || t.isFirstBonus()).forEach(tech -> {
					pw.println(wiki_fmtName(tech.isBonus() ? tech.getBonusName() : tech.getName(),
							nameMappingTechnologies));

					pw.print("|cost = ");
					pw.printf("Time, %s", wiki_fmtDouble(tech.getTime()));
					tech.getIngredients().entrySet().stream().sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
							.forEach(entry -> {
								pw.printf(" + %s, %d", wiki_fmtName(entry.getKey(), nameMappingItemsRecipes),
										entry.getValue());
							});
					pw.println();

					pw.println("|cost-multiplier = " + tech.getCount());
					pw.println("|expensive-cost-multiplier = " + (tech.getCount() * 4));

					if (!tech.getPrerequisites().isEmpty()) {
						pw.println("|required-technologies = " + tech.getPrerequisites().stream().sorted()
								.map(n -> wiki_fmtIconName(n, nameMappingTechnologies))
								.collect(Collectors.joining(" + ")));
					}

					if (!tech.isFirstBonus()) {
						Collection<String> allows = allowsMap.get(tech.getName());
						if (!allows.isEmpty()) {
							pw.println("|allows = "
									+ allows.stream().sorted().map(n -> wiki_fmtIconName(n, nameMappingTechnologies))
											.collect(Collectors.joining(" + ")));
						}
					} else {
						pw.println("|allows = " + wiki_fmtName(tech.getBonusName(), nameMappingTechnologies) + ", 2-"
								+ tech.getBonusGroup().size());
					}

					if (!tech.getRecipeUnlocks().isEmpty()) {
						pw.println("|effects = " + tech.getRecipeUnlocks().stream().sorted()
								.map(n -> wiki_fmtName(n, nameMappingItemsRecipes)).collect(Collectors.joining(" + ")));
					}

					pw.println();
				});
	}
}