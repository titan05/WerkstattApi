using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.EntityFrameworkCore;
using WerkstattApi.Models;

    public class WerkstattApiContext : DbContext
    {
        public WerkstattApiContext (DbContextOptions<WerkstattApiContext> options)
            : base(options)
        {
        }

        public DbSet<WerkstattApi.Models.Werkstatt> Werkstatt { get; set; } = default!;

public DbSet<WerkstattApi.Models.Mitarbeiter> Mitarbeiter { get; set; } = default!;
    }
