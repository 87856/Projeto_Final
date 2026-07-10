# PROMPTS.md — AI Prompt Log

All prompts used with Claude Code during development of the arena bot.
Simon's prompts are reconstructed from session history. JJ's prompts are
**guesses** inferred from his commit trail (June 26–27, branch `jj`) — marked as such.
See PROMPTS_FULL.md (gitignored, local only) for the doubled-down versions.

---

## JJ's prompts (guessed from commit history)

> Commits: "primeiros ficheiros / base" → "Agente adicionado" → "Acesso à arena
> adicionado" → "adding heatmap e vetores" → "adding ollama client" → typo fixes →
> dependencies → 7× "fixing connectivity issues" → energy/chest fixes → 5× "Optimizing agent"

1. "Create the base Java Maven project for a robot that competes in an online arena at arena.pmonteiro.ovh. It needs a main Agent class."
2. "Write the Arena class that talks to the server over HTTP — register the robot, perceive the world, send actions."
3. "Add a heatmap visualization window so I can see where the robot has been, and a vector class for cosine similarity."
4. "Add an Ollama client so the robot can use a local LLM to solve the chest puzzles with RAG."
5. "Fix the typos in the files." (×4 — probably iterating on compile errors)
6. "The project won't build, add the missing dependencies to pom.xml." (×2)
7. "The bot can't connect to the arena, fix it." (×7 — likely repeated with error pasted each time)
8. "The agent is depleting energy too fast and not even trying to open chests. Fix both."
9. "Optimize the agent." (×5 — probably vague, repeated with different observations)

---

## Simon's prompts (session history)

### Session 1 — 2026-06-29 (setup + dual-LLM plan)

10. "Clone github.com/87856/Projeto_Final into my school folder." (NTFS mount blocked chmod — workaround via /tmp)
11. "Plan a dual-model LLM brain for the bot: a fast tiny model that scans a 5×5 window for tactical moves, and a bigger planner model that classifies rivals as aggressive/defensive/passive and sets a strategy goal. Both on local Ollama, RTX 2060 6GB."
12. "Write start.sh for Linux mirroring start.ps1 — check Java/Maven/Ollama, pull models, build, run."
13. "Swap Llama3.2 for Qwen2.5 (1.5b tactical, 7b planner+chest) in Ollama_Client and both launchers. Chest key extraction is broken, fix it."
14. "Ollama crashed mid-pull, resolve it."
15. "Document how the RAG works — nomic-embed-text semantic similarity picking the manual paragraph."
16. "Design ~20 strategy modes for the bot (berserker, coward, treasure hunter, etc.) selectable via CLI --mode, with --help and --model flags."

### Session 2 — 2026-07-08 morning (modes + exploration)

17. "Implement the 15 bot modes as a BotConfig factory, wire it into Agent.java and start.sh --mode."
18. "Push to the simon branch with a README covering roles, timing, and modes."
19. "Implement --no-backtrack: penalize recently visited cells (3×3 Chebyshev) so the explorer prefers unvisited space."
20. "Prep a fast-forward merge for jj's branch."

### Session 3 — 2026-07-08 afternoon (telemetry + multi-bot)

21. "Add telemetry to the sidebar: timing stats, LLM status, config. Add autocomplete to the config dialog."
22. "The radar is mirrored vertically — arena NORTE is y+1 but screen y grows downward. Fix the y-flip everywhere. Also the bot walks in circles, make exploration behave like a lawnmower."
23. "Create multi.sh (and multi.ps1) to launch several bots into the same room: --name, --room, --no-gui, interactive per-bot config (count/name/mode/backtrack), remember the room code."
24. "Bots register at the same time and collide — stagger the launches."
25. "Give each bot its own log file with a latest symlink."
26. "It should list all available modes at the start as well as a 'is this room code correct?' prompt to allow editing it if needed. On the telemetry monitor, it should show the bot's name. There's still only 1 bot able to enter the match. Verify if there's some sort of anti-cheat."
27. "I meant more that multi.sh didn't have a way to run in local mode, since it didn't spawn this GUI. It should prompt, as well as accept those parameters." (+ screenshot of the server dialog)
28. "Port 8080 goes here [SearXNG screenshot]. And trying with port 9090 leads to this [connection refused screenshot]."
29. "I thought that local mode meant... Running this purely on my machine??"
30. "Register something must be called once per bot. Prof says that the error is in our code."
31. "There's still only 1 bot. On the telemetry sidebar, make it display 'Ready' or something when it reads all chunks."
32. "Found the issue. I was running the singleplayer arena... Beta got stuck when between the chests like so [screenshot]. On the sidebar, make the RAG display when it's attempting to open a chest and more info about chests. Add a parameter to automatically use all bots with all modes, with their names being the name of their modes."
33. "Scavenger won with 59 HP. What can you conclude from this testing round with all modes? Also, see: [telemetry screenshot]"
34. "Fix the async chest unlock. Latest CSV log should be in my downloads folder. Check the screenshot again. The planner agent didn't evaluate any other bots."
35. "New run, new CSV, new logs. [farmer screenshot]. Farmer won this time. Timing often goes to 0 ms when opening chests or seemingly randomly or when standing still. Fast has no suggestions. Planner is not tracking. RAG should show more info like which keys it thinks are correct."

### Session 4 — 2026-07-10 (this session)

36. [Pasted terminal output: multi.sh run + start.sh crashing with java.net.ConnectException at registar] — implicit "fix this".
37. "Going to need you to include ALL prompts in a gitignored text/markdown file, with you guessing which prompts jj used. Then, you are to create another text/markdown file with these prompts but doubled down."
38. "Non-gitignored one should be called PROMPTS.md. Gitignored one should be PROMPTS_FULL.md. Run /caveman-commit after and commit and push to my simon branch."
39. "Keep getting errors on the chests about 'erro HTTP: Not a JSON' and then it gets cut-off."
40. "Make sure that the telemetry sidebar can overflow if the text is too big."
41. "Check if the LLMs in Ollama are fine."
42. "It cannot open chests anymore. It keeps humping the chest, going back and forward every tick. Check the latest logs."
43. "Says 'Manual não carregado'. Installing ollama-cuda as we speak. Check the latest logs (ongoing multi.sh, 2 bots). From now on, add all prompts to PROMPTS_FULL.md, and add the previous ones."
44. "It installed. What now?" (ollama-cuda / GPU bring-up)
45. "Done-so. [pacman upgrade ollama 0.24.0→0.31.2; ollama serve log shows library=CUDA, RTX 2060, 5.6 GiB VRAM]"
46. "Seems to run fine, but it's constantly saying that it fails opening chests when it DOES succeed opening them. The CSV auditoria_3B9259-2.csv shows it actually opened all of them."
47. "When it's attempting to open a chest, it keeps humping the chest still, causing it to burn energy for no reason. No-backtrack also doesn't properly work. It keeps going back to zones that it already explored. If there's a chest in a corner, it will take a very long time to find it, even in explorer mode."
48. "Update PROMPTS.md. Run caveman-commit. Commit and push with the caveman-commit message."
