if github.branch_for_base == "master"
  if !git.modified_files.include? "docs/*"
    warn 'Did you remember to generate documentation via dokku? (Hint: `./gradlew dokka`)'
  end
  if !git.modified_files.include? "CHANGELOG.md"
    fail 'You need to edit the CHANGELOG.md file.'
  end
  android_version_change.assert_version_name_changed("teller/build.gradle")
end

if git.modified_files.include? "build.gradle" or git.modified_files.include? "teller/build.gradle"
   warn "I see you edited a `build.gradle` file. Keep in mind that unless you are simply upgrading version numbers of libraries, that is ok. If you are adding dependencies, you may get your PR denied to keep the library slim."
end

# Generate the dokka docs and output the docs to the PR to assert there are no warnings.
`./bin/install_android_sdk.sh`
generateDocsOutput = `./gradlew dokka`
markdown "Output of generating docs: \n ```#{generateDocsOutput}```"