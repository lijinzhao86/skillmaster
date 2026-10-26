# Every `run:` block in every workflow must be valid bash.
#
# **Why this exists.** A workflow's shell is not checked by anything until something runs it,
# and a deployment runs it against production. The second v19 deployment died on a single
# apostrophe in a comment inside the single-quoted remote script ("the unit's own log"): it
# closed the string early, bash read `unexpected EOF while looking for matching '`, and the
# job stopped — *after* the symlink had already been switched, so it reported failure while
# production was serving the new release. `bash -n` refuses that before anything runs.
#
# It reads YAML with Ruby's own parser, which is on the runner image already; nothing here
# needs installing.
#
# **A step that names its own shell is not bash**, so it is skipped: `shell: pwsh` is parsed by
# PowerShell itself (shell.yml does that where it is written), and checking it here would mean
# rewriting a PowerShell command as bash. Only the default step — the one that runs bash — is this
# file's business.
require "yaml"
require "tempfile"

ROOT = File.expand_path("../..", __dir__)

failures = 0
Dir.glob(File.join(ROOT, ".github/workflows/*.yml")).sort.each do |path|
  workflow = YAML.load_file(path, aliases: true)
  (workflow["jobs"] || {}).each do |job_name, job|
    (job["steps"] || []).each_with_index do |step, index|
      script = step["run"]
      next unless script
      next if step["shell"] && !["bash", "sh"].include?(step["shell"].to_s)

      label = "#{File.basename(path)} :: #{job_name} :: #{step['name'] || "step #{index}"}"
      Tempfile.create(["run", ".sh"]) do |file|
        file.write(script)
        file.flush
        if system("bash", "-n", file.path, out: File::NULL, err: File::NULL)
          puts "ok   #{label}"
        else
          failures += 1
          puts "FAIL #{label}"
          puts `bash -n #{file.path} 2>&1 | head -3`
        end
      end
    end
  end
end

puts
puts "#{failures} failing run block(s)"
exit(failures.zero? ? 0 : 1)
