
Quarks {

	classvar
		<folder,
		<>additionalFolders,
		directory,
		<>directoryUrl="https://github.com/supercollider-quarks/quarks.git",
		fetched=false,
		cache;

	*install { |name, refspec|
		var path, quark;
		if(Quarks.isPath(name).not, {
			quark = Quark(name, refspec);
			this.installQuark(quark);
			^quark
		}, {
			// local path / ~/ ./
			path = this.asAbsolutePath(name);
			if(File.exists(path).not, {
				("Quarks-install: path does not exist" + path).warn;
			});
			quark = Quark.fromLocalPath(path);
			this.installQuark(quark);
			^quark
		});
	}
	*uninstall { |name|
		// by quark name or local path
		this.installed.do { |q|
			if(q.name == name, {
				this.unlink(q.localPath)
			});
		};
	}
	*uninstallQuark { |quark|
		this.unlink(quark.localPath);
	}
	*clear {
		this.installed.do({ |quark|
			LanguageConfig.removeIncludePath(quark.localPath);
		});
		LanguageConfig.store(LanguageConfig.currentPath);
		cache = Dictionary.new;
	}
	*load { |path|
		// install a list of quarks from a text file
		var file, line, dir,re, nameRe;
		var match, localPath, url, name, refspec;
		// localPath=url[@refspec]
		re = "^([^=]+)=?([^@]+)@?(.*)$";
		// quarkname[@refspec]
		nameRe = "^([^@]+)@?(.*)$";
		path = this.asAbsolutePath(path);
		dir = path.dirname;
		if(File.exists(path).not, {
			^("Path does not exist" + path).error;
		});
		this.clear();
		file = File.open(path, "r");
		while({
			line = file.getLine();
			line.notNil
		}, {
			// resolve any paths relative to the quark file
			match = line.findRegexp(re);
			if(match.size > 0, {
				localPath = match[1][1];
				name = localPath.basename;
				url = match[2][1];
				refspec = match[3];
				if(refspec.notNil, {
					refspec = refspec[1];
				});
				this.install(this.asAbsolutePath(localPath, dir), refspec);
			}, {
				match = line.findRegexp(nameRe);
				if(match.size > 0, {
					name = match[1][1];
					refspec = match[2];
					if(refspec.notNil, {
						refspec = refspec[1];
					});
					if(Quarks.isPath(name), {
						this.install(this.asAbsolutePath(name, dir), refspec);
					}, {
						this.install(name, refspec);
					});
				}, {
					"Unreadable line: %".format(line).error;
				});
			});
		});
	}
	*save { |path|
		// save currently installed quarks to a text file
		// localPath=url[@refspec]
		var file, dir, lines;
		path = this.asAbsolutePath(path);
		dir = path.dirname;
		lines = this.installed.collect({ |quark|
			var localPath, url="", refspec;
			localPath = this.asRelativePath(quark.localPath);
			if(Git.isGit(quark.localPath), {
				url = quark.url;
				if(Git(quark.localPath).isDirty, {
					("Working copy is dirty" + quark.localPath).warn;
				}, {
					refspec = quark.refspec;
				});
			});
			if(refspec.notNil, {
				"%=%@%".format(localPath, url, refspec)
			}, {
				"%=%".format(localPath, url)
			});
		});
		file = File.open(path, "w");
		lines = lines.join(Char.nl);
		file.write(lines);
		file.close();
		^lines
	}
	*update { |name|
		// by quark name or by supplying a local path
		// resolving / ~/ ./
		Git.update(this.quarkNameAsLocalPath(name));
	}
	*installed {
		^LanguageConfig.includePaths
			.collect(Quark.fromLocalPath(_))
	}
	*isInstalled { |name|
		^LanguageConfig.includePaths.any({ |path|
			path.withoutTrailingSlash.endsWith(name)
		})
	}
	*pathIsInstalled{ |path|
		path = path.withoutTrailingSlash;
		^LanguageConfig.includePaths.any({ |apath|
			apath.withoutTrailingSlash == path
		})
	}
	*openFolder {
		this.folder.openOS;
	}
	*gui {
		^QuarksGui.new
	}

	*installQuark { |quark|
		var
			deps,
			incompatible = { arg name;
				(quark.name
					+ "reports an incompatibility with this Super Collider version"
					+ "or with other already installed quarks."
				).inform;
				false
			},
			prev = this.installed.detect({ |q| q.name == quark.name });

		if(prev.notNil and: {prev.localPath != quark.localPath}, {
			("A version of % is already installed at %".format(quark, prev.localPath)).error;
			^false
		});
		quark.checkout();
		if(quark.isCompatible().not, {
			^incompatible.value(quark.name);
		});
		quark.dependencies.do { |dep|
			var ok = dep.install();
			if(ok.not, {
				("Failed to install" + quark.name).error;
				^false
			});
		};
		this.link(quark.localPath);
		(quark.name + "installed").inform;
		^true
	}

	*link { |path|
		path = path.withoutTrailingSlash;
		if(LanguageConfig.includePaths.includesEqual(path).not, {
			path.debug("Adding path");
			LanguageConfig.addIncludePath(path);
			LanguageConfig.store(LanguageConfig.currentPath);
			^true
		});
		^false
	}
	*unlink { |path|
		path = path.withoutTrailingSlash;
		if(LanguageConfig.includePaths.includesEqual(path), {
			path.debug("Removing path");
			LanguageConfig.removeIncludePath(path);
			LanguageConfig.store(LanguageConfig.currentPath);
			^true
		});
		^false
	}

	*initClass {
		folder = Platform.userAppSupportDir +/+ "downloaded-quarks";
		additionalFolders = additionalFolders ? [];
		if(File.exists(folder).not, {
			folder.mkdir();
		});
		cache = Dictionary.new;
	}
	*at { |name|
		var q;
		^cache[name] ?? {
			q = Quark(name);
			cache.put(name, q);
			q
		}
	}
	*findQuarkURL { |name|
		^this.directory[name]
	}
	*directory {
		// just urls
		if(directory.isNil, {
			this.fetchDirectory(false);
		});
		^directory
	}
	*addFolder { |path|
		// in addition to the default downloaded-quarks
		// add folders that contain quarks to offer on the menu for installation
		// these may be private quarks, cloned working copies or folders where you
		// have manually downloaded quarks
		additionalFolders = additionalFolders.add(path.standardizePath);
	}
	*all {
		// all known quarks
		var all = Dictionary.new, f;
		// those in index
		this.directory.keysValuesDo({ |name, quarkURL|
			var q = Quark.fromDirectoryEntry(name, quarkURL);
			all[q.localPath] = q;
		});

		f = { |path|
			// \directory or \not_found, but not a file
			if(File.type(path) !== \regular, {
				path = path.withoutTrailingSlash;
				// ignore the directory
				if(path.endsWith("downloaded-quarks/quarks").not, {
					all[path] = Quark.fromLocalPath(path);
				});
			});
		};
		(Quarks.folder +/+ "*").pathMatch.do(f);
		additionalFolders.do({ |folder|
			(folder +/+ "*").pathMatch.do(f);
		});
		LanguageConfig.includePaths.do(f);
		^all.values
	}
	*fetchDirectory { |force=true|
		// will only pull every 15 minutes unless force is true
		var dirTxtPath = Quarks.folder +/+ "quarks" +/+ "directory.txt",
			fetch = true;

		if(File.exists(dirTxtPath), {
			fetch = force or: fetched.not
		});
		{
			if(fetch, { this.prFetchDirectory });
			this.prReadDirectoryFile(dirTxtPath);
		}.try({ arg err;
			("Failed to read quarks directory listing: % %".format(if(fetch, directoryUrl, dirTxtPath), err)).error;
			if(fetch, {
				// if fetch failed, try read from cache
				 if(File.exists(dirTxtPath), {
					this.prReadDirectoryFile(dirTxtPath);
				});
			}, {
				// if read from cache failed, try fetching
				if(fetch, { this.prFetchDirectory });
				this.prReadDirectoryFile(dirTxtPath);
			});
		});
	}
	*checkForUpdates {
		this.all.do { arg quark;
			if(quark.isGit, {
				quark.checkForUpdates();
			});
		}
	}
	*prReadDirectoryFile { |dirTxtPath|
		var file;
		directory = Dictionary.new;
		file = File.open(dirTxtPath, "r");
		{
			var line, kv;
			while({
				line = file.getLine();
				line.notNil;
			}, {
				kv = line.split($=);
				directory[kv[0]] = kv[1];
			});
		}.protect({
			file.close;
		});
	}
	*prFetchDirectory {
		// clone or pull
		// unless existing non-git copy exists
		var git, localPath;
		if(fetched, { ^this });
		("Fetching" + directoryUrl).debug;
		localPath = Quarks.folder +/+ "quarks";
		git = Git(localPath);
		if(Git.isGit(localPath), {
			git.pull
		}, {
			// check for manually downloaded copy
			// that exists but is not a git repo
			if(File.exists(localPath +/+ "directory.txt").not, {
				git.clone(directoryUrl)
			})
		});
		fetched = true;
	}
	*quarkNameAsLocalPath { |name|
		^if(this.isPath(name), {
			this.asAbsolutePath(name)
		}, {
			Quark(name).localPath
		});
	}
	*isPath { |string|
		^string.findRegexp("^[~\.]?/").size != 0
	}
	*asAbsolutePath { |path, relativeTo|
		^if(path.at(0).isPathSeparator, {
			path
		}, {
			if(path.at(0) == $~, {
				^path.standardizePath
			});
			// scroot is the cwd at startup
			if(path.beginsWith("./"), {
				^(relativeTo ?? {PathName.scroot}) +/+ path.copyToEnd(2)
			});
			(relativeTo ?? {PathName.scroot}) +/+ path
		})
	}
	*asRelativePath { |path, relativeToDir|
		var d;
		if(path.beginsWith(relativeToDir), {
			^"." ++ path.copyToEnd(relativeToDir)
		});
		d = Platform.userHomeDir;
		// ~/path if in home
		if(path.beginsWith(d), {
			^"~" ++ path.copyToEnd(d.size)
		});
		^path
	}
	// quarks fetch all available quark specs
	// directory.json
}
